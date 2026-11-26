Releases and CI/CD
==================

Continuous integration
----------------------

Pushes to ``main`` and ``develop`` and every pull request run ``.github/workflows/ci.yml`` (not
when they only touch ``*.md`` files, ``LICENSE``, ``NOTICE`` or the GitHub templates): the license
and comment checks,
the unit tests and lint, the Android and desktop builds, the natives' glibc check, the desktop
app started on clean Ubuntu 20.04, 22.04 and 24.04, this manual, the desktop end-to-end tests
against ROS 2 servers of Humble, Jazzy and Lyrical (each on its own Ubuntu), and the device tests
on an Android emulator.

Pull requests also run ``desktop-windows.yml`` (it can be started by hand too): the desktop tests
on Windows, the ``.msi``, and the app started (``--check``, and the 3D view with Mesa's software
OpenGL, as the runners have no GPU). ``windows-natives.yml`` builds the Windows natives (Filament's
JNI and Fast DDS, with MSVC) when their scripts change, or by hand.

Making a release
----------------

1. Set the same version in ``app/build.gradle.kts`` (``versionName``, and a higher
   ``versionCode``), ``gradle.properties`` (``vizmini2.version``) and ``ros/vizmini2/package.xml``.
2. Add its entry to both changelogs (``app/src/main/assets/CHANGELOG.md`` and
   ``CHANGELOG.es.md``): the app shows them under *Changelog* and this manual in
   :doc:`../changelog`.
3. ``python3 tools/release/version.py check v1.2.3`` checks that everything agrees. Set the date
   of the changelog entries to the release day too (it is not checked).
4. If you rebuilt native libraries, publish them first (``tools/prebuilt/publish.sh``) and commit
   ``tools/prebuilt/prebuilt.txt``: the release is built with the files it lists.
5. Start it with ``tools/release/start.sh 1.2.3`` (from ``main``, up to date and with nothing
   uncommitted): it checks the versions again, creates the **draft** GitHub release in your name
   (title and notes from the changelogs), and tags and pushes ``v1.2.3``. A plain
   ``git tag v1.2.3 && git push origin v1.2.3`` works too, but then the workflow creates the draft
   itself and the release shows as *github-actions*', not yours (nor in your followers' feed).

``.github/workflows/release.yml`` then builds every file, checks it and uploads it to that **draft**
GitHub release, to review and publish by hand:

* the APKs (one per architecture and the universal one), signed with
  the release key from the repository secrets ``ANDROID_KEYSTORE_BASE64``,
  ``ANDROID_KEYSTORE_PASSWORD``, ``ANDROID_KEY_ALIAS`` and ``ANDROID_KEY_PASSWORD``;
* the ``.deb`` and a ``.tar.gz`` of the desktop app, after installing the ``.deb`` with apt on clean
  Ubuntu 20.04, 22.04 and 24.04 and starting it (``--check`` and a 3D screenshot);
* the Windows ``.msi`` and ``.zip``, built on Windows; the ``.msi`` is installed for the current
  user, started (``--check``, and the 3D view with Mesa's software OpenGL) and uninstalled;
* this manual and the API reference as zips, ``SHA256SUMS``, and the changelog entry as release
  notes.

Nothing is uploaded to Google Play or Firebase. The Google Play bundle (``.aab``, signed with the
same key) is not in the release, as nobody can install it: download it from the run's
``android-bundle`` artifact (30 days) and upload it by hand. The R8 mappings are kept as the
``android-mapping`` artifact (90 days). The workflow can also be started by hand from the *Actions* tab to
try it (unsigned keys produce ``-debugsigned`` files).

The apt repository
------------------

When you publish a ``v*`` release, ``.github/workflows/apt.yml`` adds its ``.deb`` to VizMini2's apt
repository and signs it again (``tools/apt/update-repo.sh``). The repository is the *apt* GitHub
release (a pre-release, never the latest): its files are the ``.deb`` files, the ``Packages`` index,
the signed ``InRelease`` and the public key. It needs the secret ``APT_SIGNING_KEY``, the private
signing key (``gpg --armor --export-secret-keys <id>``, without a passphrase); its public half is
``desktopApp/packaging/linux/vizmini2-archive-keyring.gpg``, which the ``.deb`` brings along to add
the repository on its first install. By hand from the *Actions* tab, it adds the ``.deb`` of the tag
you give.

The website
-----------

When you publish that release (a ``v*`` tag: publishing the *prebuilt* release of the native
binaries deploys nothing), ``.github/workflows/site.yml`` builds this manual and the API
reference from its tag and pushes them to the
`VizMini2/VizMini2.github.io <https://github.com/VizMini2/VizMini2.github.io>`_ repository, which
GitHub Pages serves at https://vizmini2.github.io (the manual at the root, Spanish in ``es/``, the
API reference in ``api/``). It can also be started by hand, from any branch or tag, to fix the
website between releases; *dry run* only builds it and shows what would change.

It needs the secret ``SITE_DEPLOY_KEY``: create a key with ``ssh-keygen -t ed25519 -N "" -f
site_key``, add ``site_key.pub`` to the website repository's *Settings › Deploy keys* with *Allow
write access*, and ``site_key`` to this repository's *Settings › Secrets and variables › Actions*.
