# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0

"""``clip`` directive: a looping app video (the ones in the app's res/raw), with its poster screenshot.

    .. clip:: rviz
       :caption: Optional text
       :align: right   (or center; "wide" for a landscape video of the PC app, centered)
       :poster: rviz   (poster screenshot, if not the video's own)
"""
from docutils import nodes
from docutils.parsers.rst import Directive, directives


class Clip(Directive):
    required_arguments = 1
    option_spec = {'caption': directives.unchanged, 'align': directives.unchanged, 'poster': directives.unchanged}

    def run(self):
        name = self.arguments[0]
        env = self.state.document.settings.env
        up = '../' * env.docname.count('/')
        align = self.options.get('align', 'right')
        caption = self.options.get('caption', '')
        poster = self.options.get('poster', name)
        html = (
            f'<figure class="vm-clip vm-{"center vm-wide" if align == "wide" else align}">'
            f'<video src="{up}_static/clips/clip_{name}.mp4" poster="{up}_static/shots/shot_{poster}.webp" '
            f'autoplay loop muted playsinline preload="metadata"></video>'
            + (f'<figcaption>{caption}</figcaption>' if caption else '')
            + '</figure>'
        )
        return [nodes.raw('', html, format='html')]


class Shot(Directive):
    """``shot``: a screenshot (docs/shots/<lang>/shot_<name>.webp or the app's), with an optional caption.

    .. shot:: pc_camera
       :caption: Optional text
       :align: center   (right, the default, floats it next to the text; "phone" makes it narrow)
    """
    required_arguments = 1
    option_spec = {'caption': directives.unchanged, 'align': directives.unchanged, 'alt': directives.unchanged}

    def run(self):
        name = self.arguments[0]
        env = self.state.document.settings.env
        up = '../' * env.docname.count('/')
        align = self.options.get('align', 'right')
        caption = self.options.get('caption', '')
        alt = (self.options.get('alt') or caption or name).replace('"', '&quot;')
        html = (
            f'<figure class="vm-clip vm-shot vm-{align}">'
            f'<img src="{up}_static/shots/shot_{name}.webp" alt="{alt}" loading="lazy">'
            + (f'<figcaption>{caption}</figcaption>' if caption else '')
            + '</figure>'
        )
        return [nodes.raw('', html, format='html')]


def setup(app):
    app.add_directive('clip', Clip)
    app.add_directive('shot', Shot)
    return {'parallel_read_safe': True}
