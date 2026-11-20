val surefoldCore: String by rootProject.extra

dependencies {
    testImplementation("dev.surefold:surefold-assert:$surefoldCore")
    api("dev.surefold:surefold:$surefoldCore")
    api("dev.surefold:surefold-stream:$surefoldCore")
    testImplementation("dev.surefold:surefold-stream-forks:$surefoldCore")
    // The plugin rewrites sql() in tests. It is a compiler plugin, not a runtime dependency.
    add("kotlinCompilerPluginClasspath", project(":surefold-sql-plugin"))
}

// The catalog the plugin writes while compiling. A rewritten call can be checked before it runs.
val testCatalog = layout.buildDirectory.file("generated/sql/test-catalog.txt")
val mainCatalog = layout.buildDirectory.file("generated/sql/main-catalog.txt")

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.freeCompilerArgs.addAll(
        testCatalog.map { file -> listOf("-P", "plugin:dev.surefold.sql:catalog=${file.asFile.absolutePath}") },
    )
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions.freeCompilerArgs.addAll(
        mainCatalog.map { file -> listOf("-P", "plugin:dev.surefold.sql:catalog=${file.asFile.absolutePath}") },
    )
}

// The plugin writes the catalog beside the classes. The jar publishes it as a resource the registry loads.
val copySqlCatalog = tasks.register<Copy>("copySqlCatalog") {
    from(mainCatalog)
    into(layout.buildDirectory.dir("generated/sql-resources/META-INF/surefold"))
    rename { "sql.catalog" }
    dependsOn(tasks.named("compileKotlin"))
}

tasks.named<ProcessResources>("processResources") {
    from(layout.buildDirectory.dir("generated/sql-resources"))
    dependsOn(copySqlCatalog)
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dsurefold.sql.runtimeClasspath=" + mainRuntime.get().joinToString(File.pathSeparator) { it.name })
        },
    )
}
