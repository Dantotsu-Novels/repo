<div align="center">
  <img src="https://files.catbox.moe/s3mwfr.png" alt="Dantotsu Banner" width="830">

  <p align="center">
    <img src="https://img.shields.io/badge/platforms-Android%208.0%2B-06599d?labelColor=7000b7&color=1b1c2a&style=for-the-badge" alt="Platforms" />
    <a href="https://github.com/Dantotsu-Novels/repo/actions/workflows/build_push.yml"><img src="https://img.shields.io/github/actions/workflow/status/Dantotsu-Novels/repo/build_push.yml?branch=repo&label=CI&style=for-the-badge&color=1b1c2a&labelColor=7000b7" alt="CI Status" /></a>
    <a href="https://github.com/Dantotsu-Novels/repo/tree/repo"><img src="https://img.shields.io/badge/branch-repo-1b1c2a?style=for-the-badge&labelColor=7000b7" alt="Repo Branch" /></a>
    <a href="https://github.com/Dantotsu-Novels/repo/stargazers"><img src="https://img.shields.io/github/stars/Dantotsu-Novels/repo?style=for-the-badge&labelColor=7000b7&color=1b1c2a" alt="Stars" /></a>
  </p>
</div>

# Dannovels: Dantotsu Novel Extensions Repository

Official community extension repository providing novel sources for the Dantotsu novel reader.

> [!IMPORTANT]
> **Dannovels is an extension repository only.** It does not host, store, stream, or distribute any books, novels, or media files. All extensions are independent scrapers that fetch metadata and links directly from public third-party websites at runtime.
>
> **User Responsibility:** By adding this repository or installing its extensions, you agree to use them in compliance with all applicable copyright and intellectual property laws.
>
> **No Liability:** The developers and maintainers of Dannovels are not responsible for content hosted on third-party domains, website availability, or end-user actions. All inquiries regarding content should be directed to the third-party providers.

## Repository Setup

To browse, install, and update novel extensions directly within Dantotsu:

1. Open **Dantotsu**.
2. Navigate to **More** (or **Settings**) > **Extensions** > **Novel Extensions**.
3. Tap **Add Repository** (or the **+** button).
4. Enter the repository URL:
   ```text
   https://raw.githubusercontent.com/Dantotsu-Novels/repo/repo/index.json
   ```
5. Tap **Add**. Extensions will appear in the Available tab ready to install.

## Available Extensions

| Extension | Package | Version | Default Mirrors | Status |
| :--- | :--- | :--- | :--- | :--- |
| **Anna's Archive** | `ani.dantotsu.extension.novel.annaarchive` | `1.10` | `annas-archive.li`, `annas-archive.gl`, `annas-archive.pk` | Active |
| **Libgen** | `ani.dantotsu.extension.novel.libgen` | `1.8` | `libgen.im`, `libgen.la`, `libgen.is`, `libgen.rs` | Active |

## Extension Architecture

Novel extensions are modular Android packages loaded dynamically by Dantotsu's novel engine:

- **Package Identifier:** `ani.dantotsu.extension.novel.<name>`
- **Source Interface:** Each extension implements `ani.dantotsu.parsers.NovelInterface`:
  ```kotlin
  interface NovelInterface {
      suspend fun search(query: String, client: Requests): List<ShowResponse>
      suspend fun loadBook(link: String, extra: Map<String, String>?, client: Requests): Book
  }
  ```
- **Configuration:** Sources implementing `ConfigurableNovelSource` expose mirror selection and preferences directly inside Dantotsu's extension settings.
- **Reading Flow:** Dantotsu searches titles, resolves direct download endpoints via the extension, and opens books inside the native reader engine.

## Contributing

Contributions of new novel sources and fixes are welcome.

### Adding a New Source
1. Fork and clone this repository.
2. In the `src/` directory, create your extension module (e.g. `src/mysource/`).
3. Set up `build.gradle`:
   ```groovy
   ext {
       extName = 'MySource'
       extVersionName = '1.0'
       extVersionCode = 1
       extSuffix = 'mysource'
   }
   apply from: "$rootDir/common.gradle"
   ```
4. Define `AndroidManifest.xml` with label and icon.
5. Implement `NovelInterface` in package `ani.dantotsu.extension.novel.mysource`.
6. Test your scraper locally using the built-in unit tests (see below).
7. Submit a Pull Request. CI will verify and compile the APK automatically.

### Testing Extensions Locally

Each extension module includes an `ExtensionUnitTest` to quickly test search and link extraction directly on your workstation without needing an Android emulator or device:

```bash
# Run tests for a specific extension
./gradlew :src:annaarchive:test
./gradlew :src:libgen:test

# Or run all unit tests
./gradlew test
```

> [!NOTE]  
> Extension unit tests run live network requests against endpoints for local verification and scraper debugging. Automated CI release builds (`assembleRelease`) compile the APKs without triggering live network tests to prevent runner IP rate limits.

## Visitors

<img src="https://count.getloli.com/get/@:dannovels" alt=":dannovels" />

## License

Dannovels is licensed under the Unabandon Public License (UPL). More info can be found [here](LICENSE.md).
