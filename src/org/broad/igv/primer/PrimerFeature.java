package org.broad.igv.primer;

/**
 * v0.1.42：引物的 NamedFeature 包装器。
 * 交给 IGV 搜索框（SearchCommand）后，搜索结果定位/跳转用它提供坐标。
 * 坐标语义与 Primer 一致：start 0-based inclusive，end 0-based exclusive。
 */
public class PrimerFeature implements org.broad.igv.feature.NamedFeature {

    private final Primer p;

    public PrimerFeature(Primer p) {
        this.p = p;
    }

    public Primer primer() {
        return p;
    }

    // htsjdk.samtools.util.Locatable
    public String getContig() {
        return p.chr;
    }

    public int getStart() {
        return p.start;
    }

    public int getEnd() {
        return p.end;
    }

    // htsjdk.tribble.Feature
    public String getChr() {
        return p.chr;
    }

    // org.broad.igv.feature.NamedFeature
    public String getName() {
        return p.name;
    }
}
