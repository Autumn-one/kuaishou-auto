import java.io.File
import java.security.KeyStore
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
}

// =============================================================================
//  发布配置读取（release-config.json）
//
//  * release-config.json 由 .gitignore 保护：不会入库，也不会打进 APK。
//    只有两个字段：repo（owner/仓库名）与 keystorePassword。
//  * 密钥库固定 keystore/release.jks；别名从密钥库里读出来，不需要配置。
//  * 文件缺失 / 字段缺失 / 解析失败 / 密钥库不存在时一律退回默认值，构建不会失败。
//  * 优先级：-P 属性 / gradle.properties  >  环境变量（CI 用）  >  JSON 文件。
//    CI 从 Secrets 注入 KS_REPO / KS_PASSWORD，不把口令写进任何文件；
//    版本号由 CI 用 -PversionCode / -PversionName 按 git tag 覆盖。
// =============================================================================

val keystoreRelPath = "keystore/release.jks"

val cfg: Map<*, *> = run {
    val f = rootProject.file("release-config.json")
    if (!f.exists()) {
        emptyMap<String, Any?>()
    } else {
        try {
            // 编辑器（VS Code / 记事本等）常给 UTF-8 文件加 BOM，先去干净再解析
            JsonSlurper().parseText(f.readText().removePrefix("\uFEFF")) as? Map<*, *>
                ?: emptyMap<String, Any?>()
        } catch (e: Exception) {
            logger.warn("[release-config] release-config.json 解析失败，已忽略：${e.message}")
            emptyMap<String, Any?>()
        }
    }
}

fun jsonText(key: String): String? = (cfg[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

/** 口令不 trim（可能含前后空格），只有空串才算未配置。 */
fun jsonSecret(key: String): String? = (cfg[key] as? String)?.takeIf { it.isNotEmpty() }

/** 取值优先级：-P 属性 / gradle.properties > 环境变量（CI）> JSON 文件。 */
fun cfgText(prop: String, env: String, jsonKey: String): String? =
    (findProperty(prop) as String?)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv(env)?.trim()?.takeIf { it.isNotEmpty() }
        ?: jsonText(jsonKey)

fun cfgSecret(prop: String, env: String, jsonKey: String): String? =
    (findProperty(prop) as String?)?.takeIf { it.isNotEmpty() }
        ?: System.getenv(env)?.takeIf { it.isNotEmpty() }
        ?: jsonSecret(jsonKey)

/** GitHub 仓库，"owner/name" 形式；客户端「检查更新」用。 */
val cfgRepo: String? = cfgText("repo", "KS_REPO", "repo")

val cfgKeystore: File =
    (findProperty("keystoreFile") as String?)?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { rootProject.file(it.replace('/', File.separatorChar)) }
        ?: rootProject.file(keystoreRelPath)

val cfgKeystorePassword: String? = cfgSecret("keystorePassword", "KS_PASSWORD", "keystorePassword")

/**
 * 从密钥库里取出第一个私钥条目的别名。
 *
 * 别名是可以自动识别的：签名只需要「密钥库 + 口令」，alias 只是库内索引。
 * 这样配置文件里就不用多一个字段，也不会出现「别名写错但构建照跑」的坑。
 */
fun readKeyAlias(file: File, password: String): String? {
    if (!file.exists()) return null
    for (type in listOf("PKCS12", KeyStore.getDefaultType())) {
        try {
            val store = KeyStore.getInstance(type)
            file.inputStream().use { store.load(it, password.toCharArray()) }
            val aliases = store.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (store.isKeyEntry(alias)) return alias
            }
        } catch (_: Exception) {
            // 类型不匹配（或口令不对）时换下一种类型再试
        }
    }
    return null
}

val cfgKeyAlias: String? = cfgKeystorePassword?.let { readKeyAlias(cfgKeystore, it) }

/** 三项齐备（密钥库存在 + 口令正确 + 能读出别名）才启用签名，否则产出 unsigned 包。 */
val signingReady: Boolean =
    cfgKeystore.exists() && !cfgKeystorePassword.isNullOrEmpty() && !cfgKeyAlias.isNullOrEmpty()

// 版本号在 JSON 里没有配置项：发版由 CI 按 tag 覆盖，本地要用临时指定即可
val cfgVersionCode: Int =
    (findProperty("versionCode") as String?)?.trim()?.toIntOrNull() ?: 1

val cfgVersionName: String =
    (findProperty("versionName") as String?)?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0"

android {
    namespace = "com.kuaishou.auto"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kuaishou.auto"
        minSdk = 26
        targetSdk = 36
        versionCode = cfgVersionCode
        versionName = cfgVersionName
    }

    signingConfigs {
        if (signingReady) {
            create("release") {
                storeFile = cfgKeystore
                storePassword = cfgKeystorePassword
                keyAlias = cfgKeyAlias
                // PKCS12 只支持单一口令，密钥口令必然等于库口令
                // （实测 keytool：PKCS12 密钥库不支持不同的存储和密钥口令）
                keyPassword = cfgKeystorePassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 未配置密钥库时为 null，产出 app-release-unsigned.apk（维持原状）
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

// 每次构建打印一次生效结果，便于确认仓库、签名与版本号（不打印任何口令）
gradle.taskGraph.whenReady {
    logger.lifecycle(
        "[release-config] repo=" + (cfgRepo ?: "(未配置)") +
            " versionName=" + cfgVersionName +
            " versionCode=" + cfgVersionCode +
            " signed=" + (if (signingReady) "yes (alias=" + cfgKeyAlias + ")" else "no (unsigned)")
    )
}
