"""Populate the ARM64 runtime directories from the pinned upstream APK."""

import argparse
import hashlib
from pathlib import Path
from shutil import copyfileobj
from zipfile import ZipFile


APK_SHA256 = "40c2d2612533f28fef2c0bb4acf3cb80d6d45bfede405c21e73dcce613aed718"
ABI = "arm64-v8a"
RUNTIME = Path("runtime/out", ABI)
TAWCROOT = Path("runtime/out-tawcroot/jniLibs", ABI)
ASSETS = (
    "manifest.json",
    "rootfs-arm64-v8a.tar.xz",
    "licenses/libandroid-shmem-copyright.txt",
    "licenses/libtalloc-notice.txt",
    "licenses/proot-COPYING.txt",
)
NATIVE_LIBS = (
    "libandroid-shmem.so",
    "libproot-loader.so",
    "libproot.so",
    "libsqlite3.so",
    "libtalloc.so",
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("checkout", type=Path)
    args = parser.parse_args()

    root = args.checkout.resolve()
    if not (root / "tooling/check_runtime_bundle.py").is_file():
        parser.error("checkout must be the root of the DSH Go source tree")

    with args.apk.open("rb") as apk:
        if hashlib.file_digest(apk, "sha256").hexdigest() != APK_SHA256:
            parser.error("APK SHA-256 does not match DSH Go 1.2.0 ARM64")

        files = {
            **{f"assets/runtime/{asset}": RUNTIME / "assets/runtime" / asset for asset in ASSETS},
            **{f"lib/{ABI}/{lib}": RUNTIME / "jniLibs" / ABI / lib for lib in NATIVE_LIBS},
            f"lib/{ABI}/libtawcroot.so": TAWCROOT / "libtawcroot.so",
        }
        with ZipFile(apk) as archive:
            for member, target in files.items():
                archive.getinfo(member)
                if (root / target).exists():
                    parser.error(f"runtime file already exists: {target}")
            for member, target in files.items():
                destination = root / target
                destination.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(member) as source, destination.open("xb") as output:
                    copyfileobj(source, output)

    print(f"Prepared {len(files)} ARM64 runtime files.")


if __name__ == "__main__":
    main()
