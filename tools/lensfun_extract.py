# Extracts distortion profiles for lenses in lenses.csv from a Lensfun checkout (https://github.com/lensfun/lensfun).
# Usage: python3 tools/lensfun_extract.py <lensfun>/data/db android/core/src/main/resources/data/distortion.csv RecceKit/Sources/RecceKit/Data/lenses.csv
# MAP lists exact optical-design matches only. Lensfun data: CC BY-SA 3.0.
import csv,glob,sys,xml.etree.ElementTree as ET
L=sys.argv[1]; OUT=sys.argv[2]
MAP={
 'sigma_art_18_35_f18':'Sigma 18-35mm f/1.8 DC HSM [A]',
 'sigma_art_50_100_f18':'Sigma 50-100mm f/1.8 DC HSM Art',
 'sigma_art_24_70_f28_os':'Sigma 24-70mm F2.8 DG OS HSM | Art 017',
 'sigma_art_24_105_f4':'Sigma 24-105mm f/4.0 DG OS HSM [A]',
 'sigma_art_20_f14':'Sigma 20mm f/1.4 DG HSM | Art 015',
 'sigma_art_24_f14':'Sigma 24mm f/1.4 DG HSM | [A] Art 015',
 'sigma_art_28_f14':'Sigma 28mm F1.4 DG HSM | A',
 'sigma_art_35_f14':'Sigma 35mm f/1.4 DG HSM | A',
 'sigma_art_50_f14':'Sigma 50mm f/1.4 DG HSM [A]',
 'sigma_art_105_f14':'105mm F1.4 DG HSM | Art 018',
 'sigma_art_24_70_f28_dgdn':'24-70mm F2.8 DG DN | Art 019',
 'sigma_art_24_70_f28_dgdn_ii':'24-70mm F2.8 DG DN II | Art 024',
 'sigma_c_28_70_f28_dgdn':'28-70mm F2.8 DG DN | Contemporary 021',
 'sigma_c_16_28_f28_dgdn':'16-28mm F2.8 DG DN | Contemporary 022',
 'sigma_c_18_50_f28_dcdn':'Sigma 18-50mm F2.8 DC DN | Contemporary 021',
 'canon_ef_16_35_f28l_iii':'Canon EF 16-35mm f/2.8L III USM',
 'canon_ef_16_35_f4l_is':'Canon EF 16-35mm f/4L IS USM',
 'canon_ef_17_40_f4l':'Canon EF 17-40mm f/4L USM',
 'canon_ef_24_70_f28l_ii':'Canon EF 24-70mm f/2.8L II USM',
 'canon_ef_24_105_f4l_is_ii':'Canon EF 24-105mm f/4L IS II USM',
 'canon_ef_35_f14l_ii':'EF35mm f/1.4L II USM (750)',
 'canon_ef_50_f12l':'Canon EF 50mm f/1.2L USM',
 'canon_ef_85_f14l_is':'Canon EF 85mm f/1.4L IS USM',
 'canon_ef_100_f28l_macro_is':'Canon EF 100mm f/2.8L Macro IS USM',
 'canon_efs_17_55_f28_is':'Canon EF-S 17-55mm f/2.8 IS USM',
 'panasonic_lumix_12_35_f28_ii':'Lumix G X Vario 12-35mm f/2.8 II Asph. Power OIS',
 'panasonic_leica_15_f17':'Leica DG Summilux 15mm f/1.7 Asph.',
 'panasonic_leica_25_f14':'Leica DG Summilux 25mm f/1.4 Asph.',
 'olympus_12_100_f4_pro':'Olympus M.Zuiko Digital ED 12-100mm f/4.0 IS Pro',
 'olympus_7_14_f28_pro':'Olympus M.Zuiko Digital ED 7-14mm f/2.8 Pro',
 'olympus_8_25_f4_pro':'OLYMPUS M.8-25mm F4.0',
 'olympus_17_f12_pro':'Olympus M.Zuiko Digital ED 17mm f/1.2 Pro',
 'olympus_25_f12_pro':'Olympus M.Zuiko Digital ED 25mm f/1.2 Pro',
 'olympus_45_f12_pro':'Olympus M.Zuiko Digital ED 45mm f/1.2 Pro',
 'sony_gm_24_70_f28_ii':'FE 24-70mm f/2.8 GM II',
 'sony_gm_24_f14':'FE 24mm f/1.4 GM',
 'sony_gm_35_f14':'FE 35mm f/1.4 GM (SEL35F14GM)',
 'sony_gm_50_f12':'FE 50mm f/1.2 GM',
 'sony_gm_50_f14':'FE 50mm f/1.4 GM',
 'sony_gm_85_f14':'FE 85mm f/1.4 GM',
}
ours={r['id'] for r in csv.DictReader(open(sys.argv[3],encoding='utf-8'))}
lenses={}
for f in sorted(glob.glob(L+'/*.xml')):
    for lens in ET.parse(f).getroot().iter('lens'):
        m=lens.findtext('model')
        if m in MAP.values() and m not in lenses: lenses[m]=(lens,f.split('/')[-1])
rows=[]; miss=[]
def ar(s):
    if not s: return 1.5
    if ':' in s: a,b=s.split(':'); return float(a)/float(b)
    return float(s)
for lid,m in MAP.items():
    assert lid in ours, lid
    if m not in lenses: miss.append(lid); continue
    lens,fn=lenses[m]
    crop=float(lens.findtext('cropfactor') or 1); aspect=ar(lens.findtext('aspect-ratio'))
    for cal in lens.findall('calibration'):
        c_crop=float(cal.get('cropfactor',crop)); c_ar=ar(cal.get('aspect-ratio')) if cal.get('aspect-ratio') else aspect
        for d in cal.findall('distortion'):
            mod=d.get('model'); g=lambda k: d.get(k,'0')
            if mod=='ptlens': t=(g('a'),g('b'),g('c'))
            elif mod=='poly3': t=(g('k1'),'0','0')
            elif mod=='poly5': t=(g('k1'),g('k2'),'0')
            else: continue
            rows.append([lid,d.get('focal'),mod,*t,f"{c_crop:g}",f"{c_ar:.4g}",d.get('real-focal',''),m,'lensfun '+fn])
rows.sort(key=lambda r:(r[0],float(r[1])))
w=csv.writer(open(OUT,'w',encoding='utf-8',newline=''),lineterminator='\n')
w.writerow(['lens_id','focal_mm','model','t1','t2','t3','calib_crop','calib_aspect','real_focal_mm','lensfun_model','source'])
w.writerows(rows)
print(len(rows),'rows', len(MAP)-len(miss),'lenses; missing',miss)
