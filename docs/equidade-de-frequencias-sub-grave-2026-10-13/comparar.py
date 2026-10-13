import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from pathlib import Path
import csv
out=Path(__file__).parent
fs, qts, vas, fb, ql = 41.1, 1.351, 35.1, 35., 7.
f=np.geomspace(15,200,1600)
def response(f,vb):
    s=2j*np.pi*np.asarray(f)
    ws=2*np.pi*fs; wb=2*np.pi*fb; a=vas/vb
    den=s**4+(ws/qts+wb/ql)*s**3+(ws**2*(1+a)+wb**2+ws*wb/(qts*ql))*s**2+(ws*wb**2/qts+ws**2*wb/ql)*s+ws**2*wb**2
    return 20*np.log10(np.abs(s**4/den))

def sealed(f,vb):
    fc=fs*np.sqrt(1+vas/vb); qc=qts*np.sqrt(1+vas/vb)
    s=2j*np.pi*np.asarray(f); wc=2*np.pi*fc
    return 20*np.log10(np.abs(s**2/(s**2+s*wc/qc+wc**2)))
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':12})
fig,ax=plt.subplots(figsize=(12,7.2),dpi=160)
fig.patch.set_facecolor('#f5f7fb'); ax.set_facecolor('white')
vent=response(f,45); seal=sealed(f,40)
vlow=response(f,40); vhigh=response(f,50)
slow=sealed(f,37); shigh=sealed(f,43)
ax.fill_between(f,np.minimum(vlow,vhigh),np.maximum(vlow,vhigh),color='#1769aa',alpha=.13,label='Dutada: cenários de 40–50 L')
ax.fill_between(f,np.minimum(slow,shigh),np.maximum(slow,shigh),color='#e27c28',alpha=.13,label='Selada: cenários de 37–43 L')
ax.semilogx(f,vent,color='#1769aa',lw=3,label='Caixa anterior: dutada equivalente, 45 L / 35 Hz')
ax.semilogx(f,seal,color='#e27c28',lw=3,label='Caixa do desenho: selada, 40 L úteis')
ax.axhline(0,color='#64748b',lw=1)
ax.axvline(35,color='#1769aa',ls=':',lw=1.3)
ax.set_xlim(15,200);ax.set_ylim(-30,12)
ticks=[15,20,25,30,35,40,50,60,80,100,150,200]
ax.set_xticks(ticks);ax.set_xticklabels([str(t) for t in ticks]);ax.minorticks_off()
ax.set_yticks(np.arange(-30,15,5));ax.grid(True,color='#e4e8ef')
ax.set_xlabel('Frequência (Hz)');ax.set_ylabel('Resposta relativa (dB)')
ax.legend(loc='lower right',fontsize=10,framealpha=.96)
fig.suptitle('UNF125: caixa anterior × caixa selada do desenho',x=.08,ha='left',fontsize=19,fontweight='bold',y=.97)
fig.text(.08,.915,'MESMO FALANTE E MESMA REFERÊNCIA • simulação de pequenas amplitudes • sem filtros ou ganho de ambiente',fontsize=10.5,color='#596579')
fig.text(.08,.065,'Desenho: 56 × 41 cm; profundidade superior 19 cm / inferior 30 cm. Volume útil ainda estimado.\n0 dB = referência assintótica; não é SPL medido. Fs = 41,1 Hz | Qts = 1,351 | Vas = 35,1 L | dutada: QL = 7.',fontsize=10,color='#596579')
fig.subplots_adjust(left=.08,right=.97,top=.86,bottom=.19)
fig.savefig(out/'comparacao-dutada-selada.png',facecolor=fig.get_facecolor())
fig.savefig(out/'comparacao-dutada-selada.svg',facecolor=fig.get_facecolor())
points=np.array([20,25,30,35,40,50,60,80,100])
print('table',list(zip(points,np.round(response(points,45),1),np.round(sealed(points,40),1))))
for name,y in [('dutada',vent),('selada',seal)]:
    i=np.argmax(y);print(name,'peak',f[i],y[i])
print('selada Fc Qtc',fs*np.sqrt(1+vas/40),qts*np.sqrt(1+vas/40))
with (out/'comparacao-dutada-selada.csv').open('w',newline='',encoding='utf-8-sig') as fp:
    writer=csv.writer(fp);writer.writerow(['frequencia_hz','dutada_45L_35Hz_db','selada_40L_db']);writer.writerows(zip(f,vent,seal))
