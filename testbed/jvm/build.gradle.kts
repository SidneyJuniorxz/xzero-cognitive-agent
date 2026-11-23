import java.util.Properties

plugins {
    kotlin("jvm") version "2.1.0"
}

/*
 * Banco de testes fora do celular.
 *
 * Compila as FONTES REAIS de app/src/main/java (sem copiar, sem alterar) contra o
 * android.jar da plataforma 35 em `compileOnly`, e executa os testes na JVM com o
 * Robolectric fornecendo a implementação do Android em tempo de execução.
 *
 * Nada aqui escreve em app/. Se um arquivo novo aparecer no app, ele entra no
 * teste automaticamente na próxima compilação.
 */

repositories {
    mavenCentral()
    google()
}

kotlin { jvmToolchain(17) }

val repoRoot: File = rootDir.parentFile.parentFile
val appDir: File = File(repoRoot, "app")

/**
 * Onde está o android.jar. Procura, nesta ordem:
 *   1. -PandroidSdk=/caminho/do/sdk
 *   2. ANDROID_HOME / ANDROID_SDK_ROOT
 *   3. sdk.dir do local.properties da raiz deste checkout
 *   4. caminhos usuais (~/Android/Sdk, ~/android-sdk, ~/Library/Android/sdk)
 *
 * local.properties é ignorado pelo git, então cada checkout/worktree precisa do seu
 * (ou de ANDROID_HOME exportado) — veja testbed/README.md.
 */
val sdkDir: File by lazy {
    val fromProps = File(repoRoot, "local.properties").takeIf { it.isFile }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }.getProperty("sdk.dir")
    }
    val candidates = listOfNotNull(
        findProperty("androidSdk") as String?,
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        fromProps,
        "${System.getProperty("user.home")}/Android/Sdk",
        "${System.getProperty("user.home")}/android-sdk",
        "${System.getProperty("user.home")}/Library/Android/sdk",
    )
    candidates.map(::File).firstOrNull { File(it, "platforms").isDirectory }
        ?: error(
            "Android SDK não encontrado. Use uma destas opções:\n" +
                "  ./gradlew -p testbed/jvm test -PandroidSdk=/caminho/do/android-sdk\n" +
                "  export ANDROID_HOME=/caminho/do/android-sdk\n" +
                "  echo 'sdk.dir=/caminho/do/android-sdk' > ${File(repoRoot, "local.properties")}\n" +
                "Procurei em: " + candidates.joinToString(", ")
        )
}

val androidApi = (findProperty("androidApi") as String?) ?: "35"
val androidJar: File by lazy {
    File(sdkDir, "platforms/android-$androidApi/android.jar").also {
        require(it.isFile) { "android.jar não encontrado em ${it.absolutePath}" }
    }
}

/** O AAR do Sherpa-ONNX só é necessário para COMPILAR (TTS neural / wake word offline). */
val sherpaClasses: Provider<Directory> = layout.buildDirectory.dir("sherpa")
val extractSherpa by tasks.registering(Copy::class) {
    val aar = File(appDir, "libs/sherpa-onnx-2.0.1.aar")
    onlyIf { aar.isFile }
    from(zipTree(aar)) { include("classes.jar") }
    into(sherpaClasses)
}

/**
 * Stub do R gerado a partir das referências `R.<tipo>.<nome>` que existem nas fontes.
 * O AGP geraria isso a partir de res/; aqui só precisamos dos identificadores para
 * a compilação fechar — nenhum teste infla layout nem carrega bitmap de verdade.
 */
val generatedRDir: Provider<Directory> = layout.buildDirectory.dir("generated/r")
val generateRStub by tasks.registering {
    val srcDir = File(appDir, "src/main/java")
    val outDir = generatedRDir
    inputs.dir(srcDir)
    outputs.dir(outDir)
    doLast {
        val rx = Regex("""\bR\.([a-z]+)\.([A-Za-z0-9_]+)""")
        val byType = sortedMapOf<String, MutableSet<String>>()
        srcDir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            rx.findAll(f.readText()).forEach { m ->
                byType.getOrPut(m.groupValues[1]) { sortedSetOf() } += m.groupValues[2]
            }
        }
        val pkgDir = File(outDir.get().asFile, "com/xzero/agentephone").apply { mkdirs() }
        var next = 0x7f080001
        File(pkgDir, "R.kt").writeText(buildString {
            appendLine("// GERADO pelo testbed — não editar. Substitui o R do AGP só para compilar na JVM.")
            appendLine("package com.xzero.agent")
            appendLine()
            appendLine("object R {")
            byType.forEach { (type, names) ->
                appendLine("    object $type {")
                names.forEach { appendLine("        const val $it: Int = ${next++}") }
                appendLine("    }")
            }
            appendLine("}")
        })
    }
}

/**
 * O Robolectric precisa de androidx.test em tempo de execução, e esses artefatos são
 * .aar — que um projeto Kotlin/JVM puro não desempacota. Extraímos o classes.jar de
 * cada um, do mesmo jeito que fazemos com o Sherpa.
 */
val aarDeps: Configuration by configurations.creating { isTransitive = false }
val aarJarsDir: Provider<Directory> = layout.buildDirectory.dir("aar-jars")
val extractAars by tasks.registering {
    inputs.files(aarDeps)
    outputs.dir(aarJarsDir)
    doLast {
        val dir = aarJarsDir.get().asFile
        // o classes.jar vem somente-leitura de dentro do .aar: limpa antes de reescrever
        dir.deleteRecursively()
        dir.mkdirs()
        aarDeps.resolvedConfiguration.resolvedArtifacts.forEach { art ->
            val target = File(dir, "${art.moduleVersion.id.name}-${art.moduleVersion.id.version}.jar")
            copy {
                from(zipTree(art.file)) { include("classes.jar") }
                into(dir)
                rename { target.name }
                filePermissions { unix("rw-r--r--") }
            }
        }
    }
}

sourceSets {
    main {
        kotlin.setSrcDirs(listOf(File(appDir, "src/main/java"), generatedRDir))
        resources.setSrcDirs(emptyList<File>())
    }
}

tasks.named("compileKotlin") { dependsOn(generateRStub, extractSherpa) }

dependencies {
    compileOnly(files(androidJar))
    compileOnly(fileTree(sherpaClasses) { include("*.jar") })

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.json:json:20240303")

    // android.jar entra também no classpath dos testes — é o que o AGP faz com o
    // "mockable android jar": o Robolectric carrega as classes de verdade no seu
    // próprio sandbox, e o android.jar só serve de esqueleto para o runner.
    testImplementation(files(androidJar))
    testCompileOnly(fileTree(sherpaClasses) { include("*.jar") })

    aarDeps("androidx.test:monitor:1.7.2@aar")
    aarDeps("androidx.test:core:1.6.1@aar")
    testRuntimeOnly(fileTree(aarJarsDir) { include("*.jar") })

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation(kotlin("reflect"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

tasks.named("compileTestKotlin") { dependsOn(extractAars) }

tasks.withType<Test>().configureEach {
    dependsOn(extractAars)
    useJUnit()
    // o LLM falso troca o handler de https da JVM por reflexão (ver support/FakeLlm.kt)
    jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED")
    systemProperty("robolectric.logging", "stdout")
    testLogging {
        events("passed", "failed", "skipped")
        // ./gradlew -p testbed/jvm test -PshowLogs=true  -> mostra println/log do agente
        showStandardStreams = findProperty("showLogs") == "true"
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
