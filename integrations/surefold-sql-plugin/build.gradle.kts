import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// The compiler that loads this plugin is the Gradle JVM. The plugin jar stays readable there.
// Tests run on the project toolchain, with surefold-sql, so they are not pinned to that older target.
tasks.named<KotlinCompile>("compileKotlin") {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

tasks.named<JavaCompile>("compileJava") {
    options.release.set(21)
}

val surefoldCore: String by rootProject.extra
val kotlinVersion = rootProject.extra["surefoldKotlinVersion"]

val snippetRuntime = configurations.dependencyScope("snippetRuntime")
val snippetLibraries = configurations.resolvable("snippetLibraries") {
    extendsFrom(snippetRuntime.get())
}

dependencies {
    testImplementation("dev.surefold:surefold-assert:$surefoldCore")
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
    testImplementation(project(":surefold-sql"))
    testImplementation("dev.surefold:surefold:$surefoldCore")
    testImplementation("dev.surefold:surefold-stream:$surefoldCore")
    add("snippetRuntime", project(":surefold-sql"))
}

val snippetPluginPath = layout.buildDirectory.file("plugin-path.txt")
val snippetClasspath = layout.buildDirectory.file("snippet-classpath.txt")

abstract class WriteSnippetInputs : DefaultTask() {
    @get:InputFile
    abstract val pluginJar: RegularFileProperty

    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:OutputFile
    abstract val pluginPath: RegularFileProperty

    @get:OutputFile
    abstract val compileClasspath: RegularFileProperty

    @TaskAction
    fun write() {
        pluginPath.get().asFile.writeText(pluginJar.get().asFile.absolutePath)
        compileClasspath.get().asFile.writeText(
            runtimeClasspath.elements.get().joinToString("\n") { it.asFile.absolutePath },
        )
    }
}

val writeSnippetInputs = tasks.register<WriteSnippetInputs>("writeSnippetInputs") {
    pluginJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    runtimeClasspath.from(snippetLibraries)
    pluginPath.set(snippetPluginPath)
    compileClasspath.set(snippetClasspath)
}

tasks.named<Test>("test") {
    dependsOn(writeSnippetInputs)
    // Each story runs the Kotlin compiler in this JVM, and Surefold runs a suite's stories side by side, so the default
    // 512m heap ran out on CI (main runs 42695595234, 42700230911, 42704599667: OutOfMemoryError in SqlPluginSuite).
    maxHeapSize = "2g"
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.sql.plugin.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
