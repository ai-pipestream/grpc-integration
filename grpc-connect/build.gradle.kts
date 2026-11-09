plugins {
    `java-library`
    `java-test-fixtures`
}

description = "Kafka Connect scaffolding for gRPC Sync sources; connectors extend ChangeSourceConnector / ChangeSourceTask"

dependencies {
    api(libs.grpc.stub)
    api(libs.protobuf.java)
    // Supplied by the Connect worker at runtime.
    compileOnly(libs.kafka.connect.api)
    compileOnly(libs.kafka.clients)

    testFixturesCompileOnly(libs.kafka.connect.api)
    testFixturesCompileOnly(libs.kafka.clients)

    testImplementation(libs.kafka.connect.api)
    testImplementation(libs.kafka.clients)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}
