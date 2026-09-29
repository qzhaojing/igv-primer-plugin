import zipfile, os, traceback
orig = 'F:/zhaojing/IGV_2.3.80/IGV_2.3.80_jre/lib/igv.jar'
copy = '_test_igv.jar'
plugin_jar = 'PrimerPlugin.jar'
MARK = 'org.broad.igv.primer.PrimerPlugin'
REG = 'org/broad/igv/ui/resources/builtin_plugin_list.txt'

try:
    lines = []
    lines.append('orig 存在: %s' % os.path.exists(orig))
    lines.append('copy 存在: %s' % os.path.exists(copy))
    lines.append('plugin_jar 存在: %s' % os.path.exists(plugin_jar))

    def info(p):
        z = zipfile.ZipFile(p)
        n = z.namelist()
        sig = [x for x in n if x.upper().endswith(('.SF', '.RSA', '.DSA')) or 'CODESIGN' in x.upper()]
        reg = z.read(REG).decode('utf-8', 'replace')
        pc = {x: z.read(x) for x in n if x.startswith('org/broad/igv/primer/') and x.endswith('.class')}
        z.close()
        return sig, (MARK in reg), pc

    if os.path.exists(plugin_jar):
        z = zipfile.ZipFile(plugin_jar)
        pc = [x for x in z.namelist() if x.endswith('.class') and 'org/broad/igv/primer/' in x]
        z.close()
        lines.append('PrimerPlugin.jar 类数: %d' % len(pc))

    so, ro, po = info(orig)
    sc, rc, pc = info(copy)
    lines.append('原 jar  : 签名残留=%s 注册=%s 插件类数=%d' % (so, ro, len(po)))
    lines.append('副本jar: 签名残留=%s 注册=%s 插件类数=%d' % (sc, rc, len(pc)))
    lines.append('注册一致: %s' % (ro == rc))
    lines.append('插件类数一致: %s' % (len(po) == len(pc)))
    lines.append('所有插件类字节一致: %s' % (po == pc))
    lines.append('含 BoundedPopupMenu: %s' % any('BoundedPopupMenu' in x for x in pc))

    with open('result.txt', 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    print('written result.txt')
except Exception:
    with open('err.txt', 'w', encoding='utf-8') as f:
        f.write(traceback.format_exc())
    print('error -> err.txt')

