#!/usr/bin/env python3
"""Render exported Zombies world interactions with the existing atelier and IA analyzers.

Input geometry/timings come from ZombiesArenaGeneratorTest's opt-in export. Native
double-chest lids, audio, AI, lighting and inputs remain native-client observations.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import math
from pathlib import Path
import subprocess
import sys


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--export', type=Path, required=True)
    parser.add_argument('--grounding', type=Path, required=True)
    parser.add_argument('--client-jar', type=Path, required=True)
    parser.add_argument('--ops-root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    atelier = args.ops_root / 'scripts/location-atelier'
    spec = importlib.util.spec_from_file_location('zombies_vanilla_assets', atelier / 'vanilla_assets.py')
    assets = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = assets
    spec.loader.exec_module(assets)
    volume = json.loads((args.export / 'scene.blocks.json').read_text())
    timing = json.loads((args.export / 'interactions.json').read_text())
    names = [entry['block'] for entry in volume['materials']]
    blocks = [(x - 28, y + 60, z - 28, names[material]) for x, y, z, material in volume['blocks']]
    palette_names = {name for *_, name in blocks}
    # Atelier explicitly supports a static single-chest approximation, not native double lids.
    chest = 'minecraft:chest[facing=south,type=single,waterlogged=false]'
    palette_names = {chest if name.startswith('minecraft:chest[') else name for name in palette_names}
    palette_names.add('minecraft:spruce_planks')
    palette_input = args.output / 'palette-input.json'
    palette_input.write_text(json.dumps({'palette': {str(i): name for i, name in enumerate(sorted(palette_names))}}))
    palette = assets.bake(str(args.client_jar), str(palette_input))
    source_items = ['gold_g36', 'gold_aek_971', 'gold_rpl20', 'gold_vepr_12', 'gold_m1_garand',
                    'gold_vss_vintorez', 'gold_mcmillan_new', 'vs_rifle_double']
    identities = []
    for item in source_items:
        analysis = json.loads((args.grounding / (item + '.json')).read_text())
        identity = analysis['identity']['itemsadder_id']
        scale = timing['case']['scale']
        quads = []
        for face in analysis['render']['faces']:
            points = face['positions_item_display_base']
            a = [points[1][i] - points[0][i] for i in range(3)]
            b = [points[2][i] - points[0][i] for i in range(3)]
            normal = [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]
            length = math.sqrt(sum(v*v for v in normal))
            if length < 1e-12:
                continue
            quads.append({'vertices': [[.5 + v*scale for v in point] for point in points],
                          # IA grounding emits GL-bottom UVs; atelier consumes image-top UVs.
                          'normal': [v/length for v in normal], 'uv': [[u, 1-v] for u, v in face['uv_normalized']],
                          'texture': face['texture'], 'tint': None, 'cull': None})
        palette['blocks'][identity] = {'quads': quads, 'occludes': False}
        for key, value in analysis['render']['textures'].items():
            palette['textures'][key] = value['data_uri']
        identities.append(identity)

    def piece(key, material, x, y, z, width=1, height=1, depth=1):
        return dict(key=key, material=material, x=x, y=y, z=z, width=width, height=height, depth=depth)

    def surroundings(origin, bounds, open_gate=False):
        result = []
        for x, y, z, name in blocks:
            if not (bounds[0][0] <= x <= bounds[0][1] and bounds[1][0] <= y <= bounds[1][1] and bounds[2][0] <= z <= bounds[2][1]):
                continue
            if open_gate and name.startswith('minecraft:spruce_fence'):
                continue
            if name.startswith('minecraft:chest['):
                name = chest
            result.append(piece(f'world-{x}-{y}-{z}', name, x+.5-origin[0], y+.5-origin[1], z+.5-origin[2]))
        return result

    box_origin = (-4, 65, -21.5)
    box_base = surroundings(box_origin, ((-7, 0), (64, 69), (-25, -18)))
    box_camera = {'position': [0, 1.62, 3.5], 'target': [0, 1.7, .1], 'fovDegrees': 70}
    states = []
    phases = [0, *timing['cardsMs'], timing['rollMs'], timing['rollMs'] + timing['claimMs']]
    for index, ms in enumerate(phases):
        pieces = list(box_base)
        if ms >= timing['cardsMs'][0] and ms < phases[-1]:
            chosen = identities[(index - 1) % len(identities)] if ms < timing['rollMs'] else identities[0]
            pieces.append(piece('weapon-result', chosen, 0, timing['case']['y'] - 65, timing['case']['z'] + 21.5))
        label = 'Покой' if ms == 0 or ms == phases[-1] else ('Результат — 15 с на получение' if ms == timing['rollMs'] else 'Выбор оружия')
        states.append({'id': f'box-{ms}', 'title': f'{ms/1000:.2f} с · {label}', 'role':
            'Точные IA-модели, позы и интервалы. Двойной сундук показан статическим приближением; нативная крышка и звук не симулируются.',
            'pieces': pieces, 'camera': box_camera, 'durationMs': phases[index+1]-ms if index+1 < len(phases) else 1000})

    window = timing['window']
    window_origin = (window['x'] + .5, 65, window['z'] + .5)
    window_camera = {'position': [window['inwardX']*3.3, 1.62, window['inwardZ']*3.3],
                     'target': [0, 1.2, 0], 'fovDegrees': 70}
    for index, count in enumerate([3, 2, 1, 0, 1, 2, 3]):
        base = surroundings(window_origin, ((-28, -20), (64, 69), (-19, -10)), open_gate=count == 0)
        for board_index, board in enumerate(timing['boards'][:count]):
            base.append(piece(f'board-{board_index}', 'minecraft:spruce_planks',
                board['x']+board['width']/2-window_origin[0], board['y']+board['height']/2-window_origin[1],
                board['z']+board['depth']/2-window_origin[2], board['width'], board['height'], board['depth']))
        states.append({'id': f'window-{index}', 'title': f'Окно · {count}/3 доски · ' + ('прорыв' if index <= 3 else 'ремонт'),
            'role': 'Точные world BlockData и packet-board габариты. Удар каждые 2 с; ремонт при непрерывном Shift с прицелом — 1,5 с на доску.',
            'pieces': base, 'camera': window_camera, 'durationMs': timing['strikeMs'] if index < 3 else timing['repairMs']})
    scene = {'states': states, 'source': timing['source'], 'camera': box_camera}
    (args.output / 'scene.json').write_text(json.dumps(scene, ensure_ascii=False))
    (args.output / 'palette.json').write_text(json.dumps(palette, ensure_ascii=False))
    result = subprocess.run(['node', str(atelier / 'build-workshop-preview.mjs'), '--scene', str(args.output/'scene.json'),
        '--palette', str(args.output/'palette.json'), '--output', str(args.output/'index.html')], check=False)
    if result.returncode:
        raise SystemExit(result.returncode)
    html = (args.output/'index.html').read_text()
    html = html.replace('Мебельная мастерская · техническое превью', 'Zombies · ящик и баррикада')
    html = html.replace('Техническое превью: станки, этапы и готовая мебель; пол условный, без мира и NPC',
        'Исходная геометрия и тайминг. Офлайн: без нативного освещения, звука и проверки ввода.')
    html = html.replace('<button id="exportPng"', '<button id="playSequence" type="button">▶ Цикл 1:1</button><button id="exportPng"')
    html = html.replace("stateSelect.addEventListener('change',() => renderState(stateSelect.selectedIndex));", """
        let playback = null;
        function stopPlayback() { clearTimeout(playback); playback = null; }
        function advancePreview(index, end) {
          renderState(index, index !== (end === BOX_STATE_COUNT ? 0 : BOX_STATE_COUNT));
          if (index + 1 < end) playback = setTimeout(() => advancePreview(index + 1, end), PREVIEW_SCENE.states[index].durationMs);
        }
        document.getElementById('playSequence').addEventListener('click', () => {
          stopPlayback(); const box = stateSelect.selectedIndex < BOX_STATE_COUNT;
          advancePreview(box ? 0 : BOX_STATE_COUNT, box ? BOX_STATE_COUNT : PREVIEW_SCENE.states.length);
        });
        stateSelect.addEventListener('change',() => { stopPlayback(); renderState(stateSelect.selectedIndex); });
    """)
    html = html.replace('BOX_STATE_COUNT', str(len(phases)))
    html = html.replace("renderState(0);", "renderState(0); setView('player');")
    (args.output/'index.html').write_text(html)
    print(args.output/'index.html')


if __name__ == '__main__':
    main()
