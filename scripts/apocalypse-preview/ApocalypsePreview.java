import ru.ruscrafting.events.domain.apocalypse.*;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Export the actual chunk generator's detached blocks. The companion renderer samples vanilla textures. */
class ApocalypsePreview {
    private static final int LOW = -64, HIGH = 153;
    public static void main(String[] args) throws Exception {
        var out = Path.of(args[0]); Files.createDirectories(out);
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 68200133L;
        var layout = new ApocalypseWorldLayout(seed);
        var sites = layout.sitesNear(0, 0);
        var city = sites.stream().filter(s -> s.getKind() == ApocalypseSiteKind.CITY).findFirst().orElseThrow();
        var bunker = sites.stream().filter(s -> s.getKind() == ApocalypseSiteKind.REACTOR || s.getKind() == ApocalypseSiteKind.LAB).findFirst().orElseThrow();
        write(out.resolve("city.vol"), layout, city, 68, 68, -14, 58);
        write(out.resolve("bunker.vol"), layout, bunker, 52, 62, -53, 11);
        write(out.resolve("starter.vol"), layout, layout.getStarter(), 16, 34, -16, 13);
        try (var text=Files.newBufferedWriter(out.resolve("palette.txt"))) {
            for (var v:ApocalypseBlock.values()) { text.write(v.getBlockState()); text.newLine(); }
        }
        System.out.println("seed "+seed+"; city "+city+"; bunker "+bunker+"; starter "+layout.getStarter());
    }

    static void write(Path file, ApocalypseWorldLayout layout, ApocalypseSite site, int halfX, int halfZ, int y1, int y2) throws Exception {
        var a=site.getAnchor(); int x1=a.getX()-halfX, z1=a.getZ()-halfZ, x2=a.getX()+halfX, z2=a.getZ()+halfZ;
        int wy1=a.getY()+y1, wy2=a.getY()+y2, sx=x2-x1+1, sz=z2-z1+1;
        byte[] image = new byte[(wy2-wy1+1)*sx*sz];
        for (int cz=Math.floorDiv(z1,16); cz<=Math.floorDiv(z2,16);cz++) {
            for (int cx=Math.floorDiv(x1,16);cx<=Math.floorDiv(x2,16);cx++) {
                layout.generate(new ApocalypseChunkCanvas(cx,cz,LOW,HIGH) {
                    @Override protected void fill(int lx,int by,int lz,int ex,int ey,int ez,ApocalypseBlock state) {
                        int ax=Math.max(getMinX()+lx,x1), bx=Math.min(getMinX()+ex,x2);
                        if(ax>bx) return;
                        for (int y=Math.max(by,wy1); y<=Math.min(ey,wy2); y++) for (int z=Math.max(getMinZ()+lz,z1);z<=Math.min(getMinZ()+ez,z2);z++){
                            int off=((y-wy1)*sz+z-z1)*sx;
                            Arrays.fill(image,off+ax-x1,off+bx-x1+1,(byte)state.ordinal());
                        }
                    }
                });
            }
        }
        try(var stream=new DataOutputStream(Files.newOutputStream(file))) {
            for(int n:new int[]{sx,wy2-wy1+1,sz,x1,wy1,z1,a.getX(),a.getY(),a.getZ(),site.getKind()==ApocalypseSiteKind.REACTOR?48:site.getKind()==ApocalypseSiteKind.LAB?42:12})stream.writeInt(n);
            stream.write(image);
        }
    }
}
