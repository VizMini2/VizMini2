Releases and CI/CD
==================

Continuous integration
----------------------

Every push and pull request runs ``.github/workflows/ci.yml``: the license and comment checks,
the unit tests and lint, the Android and desktop builds, the natives' glibc check, the desktop
app started on clean Ubuntu 20.04, 22.04 and 24.04, this manual, the desktop end-to-end tests
against ROS 2 servers of Humble, Jazzy and Lyrical (each on its own Ubuntu), and the device tests
on an Android emulator.

Making a release
----------------

1. Set the same version in ``app/build.gradle.kts`` (``versionName``, and a higher
   ``versionCode``), ``gradle.properties`` (``vizmini2.version``) and ``ros/vizmini2/package.xml``.
2. Add its entry to both changelogs (``app/src/main/assets/CHANGELOG.md`` and
   ``CHANGELOG.es.md``): the app shows them under *Changelog* and this manual in
   :doc:`../changelog`.
3. ``python3 tools/release/version.py check v1.2.3`` checks that everything agrees.
4. Tag and push: ``git tag v1.2.3 && git push origin v1.2.3``.

``.github/workflows/release.yml`` then builds every file, checks it and leaves a **draft**
GitHub release to review and publish by hand:

* the APKs (one per architecture and the universal one), signed with
  the release key from the repository secrets ``ANDROID_KEYSTORE_BASE64``,
  ``ANDROID_KEYSTORE_PASSWORD``, ``ANDROID_KEY_ALIAS`` and ``ANDROID_KEY_PASSWORD``;
* the ``.deb`` and a ``.tar.gz`` of the desktop app, after installing the ``.deb`` with apt on clean
  Ubuntu 20.04 and 24.04 and starting it;
* this manual and the API reference as zips, ``SHA256SUMS``, and the changelog entry as release
  notes.

Nothing is uploaded to Google Play or Firebase. The Google Play bundle (``.aab``, signed with the
same key) is not in the release, as nobody can install it: download it from the run's
``android-bundle`` artifact (30 days) and upload it by hand. The R8 mappings are kept as the
``android-mapping`` artifact (90 days). The workflow can also be started by hand from the *Actions* tab to
try it (unsigned keys produce ``-debugsigned`` files).

The website
-----------

When you publish that release, ``.github/workflows/site.yml`` builds this manual and the API
reference from its tag and pushes them to the
`VizMini2/VizMini2.github.io <https://github.com/VizMini2/VizMini2.github.io>`_ repository, which
GitHub Pages serves at https://vizmini2.github.io (the manual at the root, Spanish in ``es/``, the
API reference in ``api/``). It can also be started by hand, from any branch or tag, to fix the
website between releases; *dry run* only builds it and shows what would change.

It needs the secret ``SITE_DEPLOY_KEY``: create a key with ``ssh-keygen -t ed25519 -N "" -f
site_key``, add ``site_key.pub`` to the website repository's *Settings › Deploy keys* with *Allow
write access*, and ``site_key`` to this repository's *Settings › Secrets and variables › Actions*.
