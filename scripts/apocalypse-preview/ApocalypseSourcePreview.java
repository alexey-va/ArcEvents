import ru.ruscrafting.events.domain.apocalypse.*;
import ru.ruscrafting.events.domain.apocalypse.source.*;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Run with production classes and their normal runtime classpath. All source archives are supplied by the operator. */
class ApocalypseSourcePreview {
    private final Map<String,Integer> palette = new LinkedHashMap<>(Map.of("minecraft:air", 0));
    private final Path output;
    ApocalypseSourcePreview(Path output) { this.output = output; }

    public static void main(String[] args) throws Exception {
        var output = Path.of(args[0]); Files.createDirectories(output);
        var assets = ApocalypseStructureAssets.Companion.load(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), null, List.of());
        var generator = new ApocalypseOriginalLayout(68200133L, assets);
        var preview = new ApocalypseSourcePreview(output);
        var starter = generator.structure(generator.getStarter());
        var lab = new ApocalypseJigsawAssembler(assets).assemble("jeffs_cursed_walking_structures:deepslatebunker", 68200133L, 0, 0, (x,z) -> 71);
        var reactor = new ApocalypseJigsawAssembler(assets).assemble("jeffs_cursed_walking_structures:nuclearreactor", 68200133L, 0, 0, (x,z) -> 71);
        verifyStarter(starter);
        preview.piece("starter", starter, null, starter.getSpawn().getY()-2, starter.getSpawn().getY()+2);
        preview.piece("laboratory", lab, "deepslatebunkermainpart", 1, 4);
        preview.piece("reactor", reactor, "nuclearreactormainpart", 17, 22);
        preview.city(generator);
        Files.write(output.resolve("palette.txt"), preview.palette.keySet());
        System.out.println("Verified source starter " + starter.getSpawn() + ", pieces " +
            starter.getPieces().stream().map(p->p.getTemplate().getId()).toList());
    }

    private static void verifyStarter(ApocalypseSourceAssembly starter) {
        var point = Objects.requireNonNull(starter.getSpawn());
        var support = new String[4];
        starter.render(Math.floorDiv(point.getX(),16), Math.floorDiv(point.getZ(),16), (x,y,z,state) -> {
            if (x==point.getX() && z==point.getZ() && y>=point.getY()-2 && y<=point.getY()+1)
                support[y-point.getY()+2] = state;
            return kotlin.Unit.INSTANCE;
        });
        if (support[0]==null || support[0].equals("minecraft:air") || !Objects.equals(support[1],"minecraft:air") ||
            !Objects.equals(support[2],"minecraft:air") || !Objects.equals(support[3],"minecraft:air"))
            throw new IllegalStateException("Original entity has no safe floor/headroom " + Arrays.toString(support));
    }
    private int index(String state) {
        String name=state.split("\\[",2)[0];
        if (name.equals("minecraft:cave_air") || name.equals("minecraft:void_air")) return 0;
        return palette.computeIfAbsent(name,k->palette.size());
    }

    private void piece(String name, ApocalypseSourceAssembly assembly, String focus, int lowY, int highY) throws Exception {
        var selected = assembly.getPieces().stream().filter(p -> focus == null || p.getTemplate().getId().endsWith(":"+focus)).toList();
        if (selected.isEmpty()) throw new IllegalStateException(name + " main template missing");
        int x0=selected.stream().mapToInt(p->p.getBounds().getMinX()).min().orElseThrow(), z0=selected.stream().mapToInt(p->p.getBounds().getMinZ()).min().orElseThrow();
        int x1=selected.stream().mapToInt(p->p.getBounds().getMaxX()).max().orElseThrow(), z1=selected.stream().mapToInt(p->p.getBounds().getMaxZ()).max().orElseThrow();
        int y0=focus==null?lowY:selected.get(0).getY()+lowY, y1=focus==null?highY:selected.get(0).getY()+highY;
        int sx=x1-x0+1, sz=z1-z0+1, sy=y1-y0+1;
        var placements=new ArrayList<String>();
        placements.add(x0+"\t"+y0+"\t"+z0);
        for(var p:assembly.getPieces()) placements.add(String.join("\t",p.getTemplate().getId(),Integer.toString(p.getX()),
            Integer.toString(p.getY()),Integer.toString(p.getZ()),Integer.toString(p.getRotation()),String.valueOf(p.getProcessor())));
        Files.write(output.resolve(name+"-placements.tsv"),placements);
        short[] blocks=new short[sx*sz*sy];
        for(int cz=Math.floorDiv(z0,16);cz<=Math.floorDiv(z1,16);cz++) for(int cx=Math.floorDiv(x0,16);cx<=Math.floorDiv(x1,16);cx++){
            assembly.render(cx,cz,(x,y,z,state)->{
                if (x>=x0 && x<=x1 && y>=y0 && y<=y1 && z>=z0 && z<=z1)
                    blocks[((y-y0)*sz+z-z0)*sx+x-x0]=(short)index(state);
                return kotlin.Unit.INSTANCE;
            });
        }
        write(name,sx,sy,sz,blocks);
    }

    private void city(ApocalypseOriginalLayout generator) throws Exception {
        var center = generator.sitesNear(0,0).stream().filter(s -> s.getKind() == ApocalypseSiteKind.CITY && !s.getId().equals("city.waterfront")).findFirst().orElseThrow();
        int cx0=Math.floorDiv(center.getAnchor().getX(),16)-2, cz0=Math.floorDiv(center.getAnchor().getZ(),16)-2;
        int sx=80, sz=80, sy=82, minY=69;
        short[] blocks=new short[sx*sy*sz];
        for(int cz=cz0;cz<cz0+5;cz++) for(int cx=cx0;cx<cx0+5;cx++) {
            int dx=(cx-cx0)*16, dz=(cz-cz0)*16;
            generator.generate(new ApocalypseChunkCanvas(cx,cz,minY,minY+sy-1) {
                @Override protected void fill(int x1,int y1,int z1,int x2,int y2,int z2,ApocalypseBlock block) {
                    short id=(short)index(block.getBlockState());
                    for(int y=y1;y<=y2;y++) for(int z=z1;z<=z2;z++) {
                        int i=((y-minY)*sz+z+dz)*sx+dx;
                        Arrays.fill(blocks,i+x1,i+x2+1,id);
                    }
                }
                @Override protected void fillState(int x,int y,int z,String state) {
                    blocks[((y-minY)*sz+z+dz)*sx+x+dx]=(short)index(state);
                }
            });
        }
        System.out.println("City " + center.getId() + ", chunks x=" + cx0 + ".." + (cx0+4) + ", z=" + cz0 + ".." + (cz0+4));
        write("city",sx,sy,sz,blocks);
    }
    private void write(String name,int sx,int sy,int sz,short[] blocks) throws Exception {
        try(var out=new DataOutputStream(Files.newOutputStream(output.resolve(name+".vol")))){
            out.writeInt(sx);out.writeInt(sy);out.writeInt(sz);
            for(short block:blocks) out.writeShort(block);
        }
    }
}
