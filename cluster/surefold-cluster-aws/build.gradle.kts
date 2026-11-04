// surefold-cluster on AWS: seeds from Cloud Map or ECS, and a DynamoDB item as the lease that breaks an even split.
// The AWS SDK on the JDK's own HTTP client, so neither Netty nor Apache's client comes with it.
// NoOtherDependenciesTest asserts the runtime classpath.
val surefoldCore: String by rootProject.extra

val awsVersion = "2.55.6"

dependencies {
    api("dev.surefold:surefold-cluster:$surefoldCore")
    listOf("servicediscovery", "ecs", "dynamodb").forEach { service ->
        api("software.amazon.awssdk:$service:$awsVersion") {
            exclude(group = "software.amazon.awssdk", module = "apache-client")
            exclude(group = "software.amazon.awssdk", module = "apache5-client")
            exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        }
    }
    runtimeOnly("software.amazon.awssdk:url-connection-client:$awsVersion")
    testImplementation("dev.surefold:surefold-assert:$surefoldCore")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.cluster.aws.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
