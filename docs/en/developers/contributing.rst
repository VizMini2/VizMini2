Contributing
============

Bug reports, ideas and pull requests are welcome.

* **License.** The code is under the Apache License 2.0 (``LICENSE`` and ``NOTICE``), and so is
  every contribution (section 5 of the license): no extra agreement to sign. The name "VizMini2"
  and its logo are not covered by the license: forks must use their own.
* **Headers.** New source files start with the same copyright and ``SPDX-License-Identifier:
  Apache-2.0`` header as the others (``tools/license_headers.py --check`` checks it).
* **Language.** Code and comments in English. Texts the user sees go to
  ``app/src/main/res/values*/strings*.xml``, in English and Spanish; the desktop app reads the
  same files.
* **Commits.** English, ``Area: description`` (for example ``RViz: resizable image panels``).
* **Tests.** Add a test next to what you change (:doc:`building`); the CI runs them on every
  pull request.

