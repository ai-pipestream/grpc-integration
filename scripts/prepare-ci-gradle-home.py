#!/usr/bin/env python3
"""Copy runner Gradle configuration into a job-local home, preserving policy."""
import argparse
from pathlib import Path
import shutil


def prepare(source: Path, destination: Path):
    if source.resolve() == destination.resolve():
        raise ValueError("The job-local home must differ from the runner home")
    destination.mkdir(parents=True, exist_ok=True)
    for name in ("init.gradle", "init.gradle.kts", "gradle.properties"):
        if (source / name).is_file():
            shutil.copy2(source / name, destination / name)
    if (source / "init.d").is_dir():
        shutil.copytree(source / "init.d", destination / "init.d", dirs_exist_ok=True)
    policy = destination / "init.d" / "forgejo-maven.gradle"
    if policy.is_file():
        # The runner image predates Gradle 9.6. Convert to a plain list before
        # filtering; retain its repository predicates, ordering and credentials.
        # Do not mutate shared runner configuration or suppress warnings.
        original = policy.read_text()
        updated = original.replace("repos.findAll {", "repos.toList().findAll {")
        policy.write_text(updated)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    prepare(args.source, args.destination)
