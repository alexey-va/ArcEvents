#!/usr/bin/env python3
"""Render the same block models, poses and native backing used by the six expedition stations."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    repo = Path(__file__).resolve().parents[2]
    parser.add_argument('--models', type=Path, default=repo / 'src/main/resources/apocalypse/workbench-displays.json')
    parser.add_argument('--ops-root', type=Path, required=True)
    parser.add_argument('--client-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--station', help='Render one station, for example T1')
    args = parser.parse_args()
    source = args.models.read_bytes()
    models = json.loads(source)
    args.output.mkdir(parents=True, exist_ok=True)
    atelier = args.ops_root / 'scripts/location-atelier'
    camera = {'position': [1.08, 1.62, 2.18], 'target': [0, .93, .22], 'fovDegrees': 62}
    titles = {'T1': 'Полевой телеграф', 'T2': 'Оружейный верстак', 'T3': 'Высокоточный оружейный верстак',
              'AMMO': 'Снаряжение патронов', 'PRESS': 'Механический пресс', 'REFINERY': 'Переработчик Пустоты'}
    states = []
    for kind, parts in models.items():
        if args.station and kind != args.station:
            continue
        pieces = [dict(key='native-dropper', material='minecraft:dropper[facing=south]', x=0, y=.5, z=0,
                       width=1, height=1, depth=1)]
        for part in parts:
            x, y, z = (value / 16 for value in part['center'])
            width, height, depth = (value / 16 for value in part['size'])
            rx, ry, rz = part.get('rotation', [0, 0, 0])
            pieces.append(dict(key=part['key'], material='minecraft:' + part['block'], x=x, y=y, z=z,
                               width=width, height=height, depth=depth, rotationX=rx, rotationY=ry, rotationZ=rz))
        states.append(dict(id=kind, title=titles[kind], pieces=pieces, camera=camera))
    if not states:
        parser.error(f'Unknown station {args.station}')
    if not args.station:
        # South-facing workbenches placed flush west-to-east. Apply the same cabinet boundary as the runtime.
        row = []
        for index, state in enumerate(states):
            offset = index - (len(states) - 1) / 2
            for original in state['pieces']:
                piece = dict(original, key=state['id'] + '-' + original['key'])
                if piece['y'] <= 16.5 / 16:
                    left, right = piece['x'] - piece['width'] / 2, piece['x'] + piece['width'] / 2
                    if index:
                        left = max(left, -.5)
                    if index + 1 < len(states):
                        right = min(right, .5)
                    if left >= right:
                        continue
                    piece['x'], piece['width'] = (left + right) / 2, right - left
                piece['x'] += offset
                row.append(piece)
        states.append(dict(id='row', title='Смежные станции', pieces=row,
                           camera={'position': [1.45, 1.62, 5.65], 'target': [0, .9, 0], 'fovDegrees': 62}))
    scene = args.output / 'scene.json'
    materials = sorted({piece['material'] for state in states for piece in state['pieces']})
    scene.write_text(json.dumps(dict(source=hashlib.sha256(source).hexdigest(), states=states, camera=camera,
                                     palette={str(i): name for i, name in enumerate(materials)}),
                                ensure_ascii=False))
    palette = args.output / 'palette.json'
    with palette.open('w') as target:
        subprocess.run([sys.executable, str(atelier / 'vanilla_assets.py'), str(args.client_jar), str(scene)],
                       stdout=target, check=True)
    template = (atelier / 'workshop-preview.template.html').read_text()
    template = template.replace('Мебельная мастерская', 'Верстаки экспедиции')
    template = template.replace('Техническое превью: станки, этапы и готовая мебель; пол условный, без мира и NPC',
                                'Геометрия верстаков и текстуры Minecraft 1.21.11. Освещение условное.')
    template = template.replace('Состояние мастерской', 'Станция').replace('renderState(0);', "renderState(0); setView('player');")
    # The shared workshop's front is -Z. These cabinets face +Z.
    template = template.replace('front: target.clone().add(new THREE.Vector3(0,0,-distance))',
                                'front: target.clone().add(new THREE.Vector3(0,0,distance))')
    template = template.replace('distance*0.66,-distance*0.72', 'distance*0.66,distance*0.72')
    template = template.replace('const floorY = Math.min(0,bounds.minY-0.08);', 'const floorY = 0;')
    template = template.replace("scene.background = new THREE.Color('#dfe1de')", "scene.background = new THREE.Color('#9a9f98')")
    template = template.replace("color:'#d5d8d4'", "color:'#737970'")
    template_path = args.output / 'template.html'
    template_path.write_text(template)
    subprocess.run(['node', str(atelier / 'build-workshop-preview.mjs'), '--scene', str(scene),
                    '--palette', str(palette), '--template', str(template_path),
                    '--output', str(args.output / 'index.html')], check=True)


if __name__ == '__main__':
    main()
