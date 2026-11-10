plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "grpc-integration"

include("grpc-sync-api")
include("grpc-sync-service")
include("grpc-okf")
include("grpc-output-spi")
include("grpc-output-filesystem")
include("grpc-output-s3")
include("grpc-mcp")
include("grpc-connect")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
