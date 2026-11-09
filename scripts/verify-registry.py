#!/usr/bin/env python3
"""Check anonymous resolution of each published snapshot's JVM artifacts."""
import argparse
import urllib.request
import xml.etree.ElementTree as ET

MODULES = (
    "grpc-sync-api", "grpc-sync-service", "grpc-okf", "grpc-output-spi",
    "grpc-output-filesystem", "grpc-output-s3", "grpc-mcp", "grpc-connect",
)


def verify(base_url, version):
    for module in MODULES:
        root = f"{base_url.rstrip('/')}/ai/pipestream/integration/{module}/{version}/"
        with urllib.request.urlopen(root + "maven-metadata.xml", timeout=30) as response:
            metadata = ET.fromstring(response.read())
        variants = {
            (node.findtext("extension"), node.findtext("classifier", "")): node.findtext("value")
            for node in metadata.findall("./versioning/snapshotVersions/snapshotVersion")
        }
        required = [("pom", ""), ("module", ""), ("jar", "")]
        if module in ("grpc-okf", "grpc-connect"):
            required.append(("jar", "test-fixtures"))
        for extension, classifier in required:
            value = variants.get((extension, classifier))
            if not value:
                raise RuntimeError(f"{module}: missing {extension}/{classifier} snapshot metadata")
            suffix = f"-{classifier}" if classifier else ""
            url = f"{root}{module}-{value}{suffix}.{extension}"
            with urllib.request.urlopen(url, timeout=30) as response:
                if not response.read(1):
                    raise RuntimeError(f"{module}: empty {extension}/{classifier}")
        print(f"{module}: anonymous snapshot resolution PASS ({len(required)} artifacts)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="https://git.rokkon.com/api/packages/ai-pipestream/maven")
    parser.add_argument("--version", default="0.1.0-SNAPSHOT")
    args = parser.parse_args()
    verify(args.base_url, args.version)
