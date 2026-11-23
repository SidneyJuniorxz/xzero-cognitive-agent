// Build independente: NÃO faz parte do build do app (o settings.gradle.kts da raiz
// inclui apenas ":app"). Rodar com:  ./gradlew -p testbed/jvm test
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
rootProject.name = "xzero-testbed"
