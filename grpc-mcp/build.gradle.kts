plugins {
    `java-library`
    application
}

description = "Combined Streamable HTTP MCP server; connector tools plug in through McpToolProvider"

dependencies {
    implementation(project(":grpc-sync-api"))
    // McpToolProvider hands out MCP SDK tool specs over a gRPC Channel.
    api(libs.grpc.stub)
    api(libs.mcp.core)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.jackson.databind)
    implementation(libs.mcp.json.jackson2)
    runtimeOnly(libs.slf4j.jdk14)
    runtimeOnly(libs.log4j.core)
    compileOnly(libs.tomcat.annotations)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(project(":grpc-sync-service"))
    testImplementation(libs.grpc.inprocess)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass = "ai.pipestream.mcp.McpServerMain"
}
