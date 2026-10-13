# Dantotsu Novel Extensions

<p align="center">
  <img src="https://files.catbox.moe/s3mwfr.png" alt="Dantotsu" />
</p>

<p align="center">
  Official community repository for Dantotsu Novel Extensions.
</p>

<p align="center">
  <a href="https://github.com/Dantotsu-Novels/repo/actions/workflows/build_push.yml">
    <img src="https://github.com/Dantotsu-Novels/repo/actions/workflows/build_push.yml/badge.svg" alt="CI Status" />
  </a>
  <a href="https://github.com/Dantotsu-Novels/repo/tree/repo">
    <img src="https://img.shields.io/badge/branch-repo-blue.svg" alt="Repo Branch" />
  </a>
  <a href="https://github.com/Dantotsu-Novels/repo/stargazers">
    <img src="https://img.shields.io/github/stars/Dantotsu-Novels/repo?style=flat&color=yellow" alt="Stars" />
  </a>
</p>

---

## 📖 Usage

### Adding the Repository to Dantotsu

To browse and install novel extensions directly inside **Dantotsu**:

1. Open **Dantotsu**.
2. Navigate to **More** (or **Settings**) → **Extensions** → **Novel Extensions**.
3. Tap **Add Repository** (or the **+** button).
4. Paste the following repository URL:
   ```text
   https://raw.githubusercontent.com/Dantotsu-Novels/repo/repo/index.json
   ```
   *(or simply `https://github.com/Dantotsu-Novels/repo`)*
5. Tap **Add**. The novel extensions will appear under the Available tab ready to install.

---

## 📚 Available Extensions

| Extension | Package | Version | Mirrors / Base URL | Status |
|---|---|---|---|---|
| **Anna's Archive** | `some.random.novels` | `1.8` | `annas-archive.gl`, `annas-archive.pk`, `annas-archive.gd` | 🟢 Active |
| **Libgen** | `some.random.libgen` | `1.6` | `libgen.la`, `libgen.bz`, `libgen.gl`, `libgen.im` | 🟢 Active |

---

## 🛠️ How Novel Extensions Work in Dantotsu

Novel extensions are lightweight APK packages that Dantotsu loads dynamically using Android reflection and `PathClassLoader`:

* **Package Identifier**: Must begin with `some.random` (e.g. `some.random.<name>`).
* **Extension Class**: Derives its class path from the application label:  
  `some.random.novelextensions.<label_lowercase>.<label>`
* **Interface**: Implements `ani.dantotsu.parsers.NovelInterface`:
  ```kotlin
  interface NovelInterface {
      suspend fun search(query: String, client: Requests): List<ShowResponse>
      suspend fun loadBook(link: String, extra: Map<String, String>?, client: Requests): Book
  }
  ```
* **Download Flow**: When a reader opens a book, Dantotsu resolves the direct EPUB link and downloads it into the native novel reader engine.

---

## 💻 Contributing

Contributions and new novel sources are welcome!

### Creating a New Extension

1. Fork this repository and clone it locally.
2. In the `src/` directory, create a new folder for your extension (e.g. `src/mysource`).
3. Set up the `build.gradle.kts`:
   ```kotlin
   extra["extName"] = "MySource"
   extra["extVersionName"] = "1.0"
   extra["extVersionCode"] = 1
   extra["extSuffix"] = "mysource"

   apply(from = rootProject.file("common.gradle"))
   ```
4. Create `AndroidManifest.xml` with:
   ```xml
   <manifest xmlns:android="http://schemas.android.com/apk/res/android">
       <application android:label="MySource" android:icon="@mipmap/ic_launcher" />
   </manifest>
   ```
5. Implement `some.random.novelextensions.mysource.MySource` implementing `NovelInterface`.
6. Submit a Pull Request. CI will automatically verify and build the extension.

---

## 🤖 Automated Version Bumping & CI/CD

This repository features an automated CI/CD pipeline modeled after **Yuzuno** and **Keiyoushi**:

* **Auto-Bumping (`bump-versions.py`)**: When `core/` or `common/` library code is updated, all dependent extensions automatically have their version code incremented.
* **Parallel Build Chunks**: Compiles extensions in matrix chunks on GitHub Actions runners.
* **Continuous Publishing**: Automatically packages release APKs, extracts app icons, and generates `index.json` and `index.min.json` on the `repo` branch.

---

## ⚖️ License & Disclaimer

```text
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

* **Disclaimer**: This project does not host any media content. All content is fetched live from public third-party services. The authors are not affiliated with any content providers or Dantotsu core maintainers.
