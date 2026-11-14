// Compare exported voxel IDs with the original NBT using the independent decoder from the native E2E fixture.
// Usage: node scripts/apocalypse-preview/verify-source.cjs OUTPUT_DIRECTORY JCWS_JAR
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { createRequire } = require('node:module');
const fixture = createRequire(path.resolve(__dirname, '../../src/test/e2e-host/package.json'));
const nbt = fixture('prismarine-nbt');
const [, , directory, jar] = process.argv;

function expectedMaterial(id, processor) {
    if (!id.startsWith('minecraft:') || id === 'minecraft:jigsaw') return null;
    if (id === 'minecraft:water' && /:(jeff|loot)$/.test(processor)) return null;
    if (id === 'minecraft:purpur_block' && processor.endsWith(':raph')) return null;
    if (id === 'minecraft:iron_ore' && processor.endsWith(':pond')) return null;
    if (id === 'minecraft:grass') return 'minecraft:short_grass';
    if (id === 'minecraft:chain') return 'minecraft:iron_chain';
    return id;
}

async function verify(scene, palette) {
    const volume = fs.readFileSync(path.join(directory, scene + '.vol'));
    const [sx, sy, sz] = [0, 4, 8].map(offset => volume.readInt32BE(offset));
    const [origin, ...placements] = fs.readFileSync(path.join(directory, scene + '-placements.tsv'), 'utf8').trimEnd().split('\n');
    const [ox, oy, oz] = origin.split('\t').map(Number);
    const expected = new Map();
    for (const placement of placements) {
        const [id, x, y, z, rotation, processor] = placement.split('\t');
        const archiveEntry = 'data/' + id.replace(':', '/structures/') + '.nbt';
        const bytes = execFileSync('unzip', ['-p', jar, archiveEntry], { maxBuffer: 16 * 1024 * 1024 });
        const template = nbt.simplify((await nbt.parse(bytes)).parsed);
        function index([lx, ly, lz]) {
            const [dx, dz] = [[lx, lz], [-lz, lx], [-lx, -lz], [lz, -lx]][Number(rotation)];
            const bx = Number(x) + dx - ox, by = Number(y) + ly - oy, bz = Number(z) + dz - oz;
            if (bx < 0 || bx >= sx || by < 0 || by >= sy || bz < 0 || bz >= sz) return null;
            return (by * sz + bz) * sx + bx;
        }
        for (const block of template.blocks) {
            const position = index(block.pos);
            const material = template.palette[block.state].Name;
            if (position === null || material === 'minecraft:structure_void' || material === 'minecraft:structure_block') continue;
            expected.set(position, expectedMaterial(material, processor));
        }
        for (const entity of template.entities) {
            if (entity.nbt.id.endsWith('_spawner_placer')) expected.delete(index(entity.pos.map(Math.floor)));
        }
    }
    let compared = 0, nonAir = 0, mismatches = 0;
    for (const [position, material] of expected) {
        if (material === null) continue;
        compared++;
        if (material !== 'minecraft:air') nonAir++;
        const actual = palette[volume.readUInt16BE(12 + position * 2)];
        if (material !== actual) {
            if (mismatches < 3) console.error(scene, position, 'expected', material, 'found', actual);
            mismatches++;
        }
    }
    console.log(`${scene}: ${compared} source voxels checked, ${nonAir} non-air, ${mismatches} material differences`);
    if (!nonAir || mismatches) process.exitCode = 1;
}

async function main() {
    if (!directory || !jar) throw new Error('Expected an exported directory and the original JCWS JAR');
    const palette = fs.readFileSync(path.join(directory, 'palette.txt'), 'utf8').trimEnd().split('\n');
    for (const scene of ['starter', 'laboratory', 'reactor']) await verify(scene, palette);
}

main().catch(error => { console.error(error); process.exitCode = 1; });
