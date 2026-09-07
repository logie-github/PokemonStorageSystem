# Package Exporter

A clean Android utility that uses Shizuku to list package directories under
`/storage/emulated/0/Android/data` and export every accessible file in any one
of them as a ZIP archive. Paths are preserved and files are streamed in chunks.

## Build

Requires JDK 17 and Android SDK 35.

```sh
./gradlew testDebugUnitTest assembleDebug
```
