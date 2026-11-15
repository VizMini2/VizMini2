# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only

# VizMini2 documentation (Sphinx + Read the Docs theme, like docs.ros.org).
# Built once per language with docs/build.sh: VIZ_LANG=en (root) and VIZ_LANG=es (es/).
import os
import sys

sys.path.insert(0, os.path.abspath('_ext'))

lang = os.environ.get('VIZ_LANG', 'en')
language = lang

project = 'VizMini2'
author = 'Juan Carlos Manzanares Serrano'
copyright = '2026, Juan Carlos Manzanares Serrano'



def _app_version():
    """versionName from app/build.gradle.kts: the version is written in a single place."""
    import re
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'build.gradle.kts')
    try:
        with open(path, encoding='utf-8') as f:
            m = re.search(r'versionName\s*=\s*"([^"]+)"', f.read())
        if m:
            return m.group(1)
    except OSError:
        pass
    return os.environ.get('VIZ_VERSION', '0.0.0')


release = version = _app_version()

# Where the APKs and the .deb are published (the draft releases of .github/workflows/release.yml, once published).
RELEASES_URL = 'https://github.com/VizMini2/VizMini2/releases'
rst_epilog = f'''
.. _releases page: {RELEASES_URL}/latest
.. |deb| replace:: ``vizmini2_{release}_amd64.deb``
'''

extensions = ['myst_parser', 'vizmini']
templates_path = ['_templates']
exclude_patterns = ['_build', '_generated']

# The website (.github/workflows/site.yml): canonical links point there.
SITE_URL = 'https://vizmini2.github.io/'
html_baseurl = SITE_URL if lang == 'en' else SITE_URL + 'es/'

html_theme = 'sphinx_rtd_theme'
html_title = 'VizMini2 documentation' if lang == 'en' else 'Documentación de VizMini2'
html_short_title = 'VizMini2'
html_logo = '_static/vizmini2.svg'
html_favicon = '_static/favicon.png'
# _generated/<language>: videos and screenshots copied from the app by build.sh.
html_static_path = ['_static', '_generated/' + lang]
html_css_files = ['custom.css']
html_show_sourcelink = False
html_copy_source = False
html_last_updated_fmt = '%Y-%m-%d'
html_theme_options = {
    'logo_only': False,
    'collapse_navigation': True,
    'navigation_depth': 3,
    'style_nav_header_background': '#0F2C44',
    'prev_next_buttons_location': 'bottom',
}
html_context = {'vm_lang': lang}
