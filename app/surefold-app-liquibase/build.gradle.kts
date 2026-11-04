// liquibase-core and the JDK's javax.sql. No driver: the DataSource is the
// application's, and so is what it connects to.
val surefoldCore: String by rootProject.extra

val liquibaseVersion = "4.32.0"

dependencies {
    api("dev.surefold:surefold-app:$surefoldCore")
    api("org.liquibase:liquibase-core:$liquibaseVersion")

    // A real Postgres, in a container.
    testImplementation(testFixtures("dev.surefold:surefold-actor-journal-jdbc:$surefoldCore"))
    testImplementation("dev.surefold:surefold-assert:$surefoldCore")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.app.liquibase.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
