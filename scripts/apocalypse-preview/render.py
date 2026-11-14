#!/usr/bin/env python3
"""Preview actual generated voxels with the local vanilla 1.21.11 client texture colors."""
import argparse
import io
import math
from pathlib import Path
import struct
import subprocess
import zipfile
import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CLIENT = Path.home() / 'Library/Application Support/PrismLauncher/libraries/com/mojang/minecraft/1.21.11/minecraft-1.21.11-client.jar'


def palette(output, client):
    names = (output / 'palette.txt').read_text().splitlines()
    aliases = dict(grass_block='grass_block_top', oak_log='oak_log_top', spruce_log='spruce_log_top',
                   barrel='barrel_top', crafting_table='crafting_table_top', blast_furnace='blast_furnace_front',
                   furnace='furnace_front', smoker='smoker_front', iron_chain='iron_chain', water='water_still',
                   cauldron='cauldron_top', lodestone='lodestone_top', dead_bush='dead_bush', lever='lever',
                   ochre_froglight='ochre_froglight_top', redstone_lamp='redstone_lamp', smooth_stone_slab='smooth_stone',
                   stone_brick_slab='stone_bricks', polished_andesite_slab='polished_andesite',
                   polished_andesite_stairs='polished_andesite', light_blue_stained_glass_pane='light_blue_stained_glass',
                   hay_block='hay_block_top', bedrock='bedrock', coal_ore='coal_ore')
    colors = []
    with zipfile.ZipFile(client) as archive:
        for state in names:
            name = state.split(':')[1].split('[')[0]
            if name == 'air':
                colors.append((0,0,0)); continue
            key=aliases.get(name, name)
            candidates=[key, key+'_side', key+'_top', key+'_front', 'stone']
            for candidate in candidates:
                try: img=Image.open(io.BytesIO(archive.read('assets/minecraft/textures/block/'+candidate+'.png'))).convert('RGBA')
                except KeyError: continue
                arr=np.asarray(img)[:img.width]; valid=arr[:,:,3]>70
                if not valid.any(): continue
                col=np.mean(arr[:,:,:3][valid],axis=0)
                if name in ('grass_block','short_grass','fern','oak_leaves','spruce_leaves'):
                    col*=np.array([0.52,0.71,0.37])
                if name=='water':col=np.array([57,95,123])
                if name in ('glass','gray_stained_glass','light_blue_stained_glass'): col=.58*col+.42*np.array([96,128,141])
                colors.append(tuple(np.clip(col,0,255).astype('uint8')));break
            else: colors.append((132,125,115))
    return names, np.asarray(colors,dtype=np.uint8)


def read(path):
    raw=path.read_bytes()
    sx,sy,sz,x1,y1,z1,ax,ay,az,depth=struct.unpack('>10i',raw[:40])
    return np.frombuffer(raw[40:],dtype=np.uint8).reshape((sy,sz,sx)).copy(),(ax-x1,ay-y1,az-z1),depth


def render(volume, colors, scale, vertical, cut=None):
    sy,sz,sx=volume.shape
    if cut is not None:volume[cut:,:,:]=0
    occupied=volume!=0
    faces={}
    for name,(dy,dz,dx) in {'top':(1,0,0),'east':(0,0,1),'south':(0,1,0)}.items():
        shifted=np.roll(occupied,(-dy,-dz,-dx),(0,1,2))
        if dy:shifted[-1]=False
        if dz:shifted[:,-1]=False
        if dx:shifted[:,:,-1]=False
        faces[name]=occupied & ~shifted
    visible=faces['top']|faces['east']|faces['south']
    ys,zs,xs=np.nonzero(visible); bx,by=sz*scale+4,sy*vertical+4
    canvas=Image.new('RGB',((sx+sz)*scale+8, (sx+sz)*scale//2+sy*vertical+14),(28,34,37)); draw=ImageDraw.Draw(canvas)
    for i in np.argsort((xs+zs)*2+ys,kind='stable'):
        x,y,z=int(xs[i]),int(ys[i]),int(zs[i]); px=bx+(x-z)*scale; py=by+((x+z)*scale)//2-y*vertical
        c=colors[volume[y,z,x]]; noise=0.96+0.06*((x*13+z*17+y*7)%13)/12
        def shade(light): return tuple((c*light*noise).clip(0,255).astype(int))
        hw=scale//2
        if faces['east'][y,z,x]:draw.polygon([(px+scale,py+hw),(px,py+scale),(px,py+scale+vertical),(px+scale,py+hw+vertical)],fill=shade(.77))
        if faces['south'][y,z,x]:draw.polygon([(px-scale,py+hw),(px,py+scale),(px,py+scale+vertical),(px-scale,py+hw+vertical)],fill=shade(.57))
        if faces['top'][y,z,x]:draw.polygon([(px,py),(px+scale,py+hw),(px,py+scale),(px-scale,py+hw)], fill=shade(1.08))
    return canvas


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',type=Path,default=ROOT/'build/reports/apocalypse-preview')
    parser.add_argument('--client',type=Path,default=DEFAULT_CLIENT)
    parser.add_argument('--seed',type=int,default=68200133)
    parser.add_argument('--classes',type=Path,default=ROOT/'build/classes/kotlin/main')
    args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    stdlib=next((Path.home()/'.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.3.0').glob('*/*.jar'))
    subprocess.run(['java','-cp',str(args.classes)+':'+str(stdlib),str(Path(__file__).with_name('ApocalypsePreview.java')),str(args.out),str(args.seed)],check=True)
    names,colors=palette(args.out,args.client)
    city,_,_=read(args.out/'city.vol'); render(city,colors,4,4).save(args.out/'city.png')
    for scene in ['bunker','starter']:
        volume,a,depth=read(args.out/(scene+'.vol')); floor=a[1]-depth
        # Remove surrounding natural rock and overburden to inspect the actual floor plan.
        allowed=np.zeros(len(names),dtype=bool)
        allowed[[i for i,n in enumerate(names) if n.split(':')[-1] in ('stone','deepslate','tuff','dirt','clay','gravel','sand','grass_block','coarse_dirt','podzol','water','ore') or '_ore' in n]]=True
        volume[allowed[volume]]=0
        # Isolate the authored underground level from unrelated nearby structures.
        volume=volume[max(0,floor-1):floor+4, max(0,a[2]-5):, :]
        render(volume,colors,6,6).save(args.out/(scene+'.png'))

if __name__=='__main__': main()
