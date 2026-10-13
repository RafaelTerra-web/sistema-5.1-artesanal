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
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':12})
fig,ax=plt.subplots(figsize=(12,7.2),dpi=160)
fig.patch.set_facecolor('#f5f7fb');ax.set_facecolor('white')
lo=response(f,40); mid=response(f,45); hi=response(f,50)
ax.fill_between(f,np.minimum(lo,hi),np.maximum(lo,hi),color='#1769aa',alpha=.12,label='Cenários: 40–50 L úteis')
ax.semilogx(f,lo,color='#7293af',lw=1.6,ls='--',label='40 L úteis / 35 Hz')
ax.semilogx(f,mid,color='#1769aa',lw=3,label='45 L úteis / 35 Hz (hipótese central)')
ax.semilogx(f,hi,color='#7293af',lw=1.6,ls=':',label='50 L úteis / 35 Hz')
ax.axhline(0,color='#6b7280',lw=1)
ax.axvline(35,color='#e1922c',ls='--',lw=1.6,label='Sintonia anunciada: 35 Hz')
i=np.argmax(mid)
ax.annotate(f'Pico do modelo: {f[i]:.0f} Hz / +{mid[i]:.1f} dB',xy=(f[i],mid[i]),xytext=(87,10.8),arrowprops={'arrowstyle':'->','color':'#1769aa'},color='#1769aa',fontsize=11)
ax.set_xlim(15,200);ax.set_ylim(-30,14)
ticks=[15,20,25,30,35,40,50,60,80,100,150,200]
ax.set_xticks(ticks); ax.set_xticklabels([str(t) for t in ticks]);ax.minorticks_off()
ax.set_yticks(np.arange(-30,15,5));ax.grid(True,color='#e4e8ef')
ax.set_xlabel('Frequência (Hz)');ax.set_ylabel('Resposta relativa (dB)')
fig.suptitle('UNLIKE UNF125 na caixa anunciada a 35 Hz',x=.08,ha='left',fontsize=20,fontweight='bold',y=.97)
fig.text(.08,.915,'SIMULAÇÃO CONDICIONAL • caixa dutada equivalente • sem ganho de carro/sala e sem filtros',fontsize=11,color='#596579')
ax.legend(loc='lower right',fontsize=10,framealpha=.95)
fig.text(.08,.065,'0 dB = referência assintótica do modelo; não é SPL absoluto nem resposta medida.\nFs = 41,1 Hz | Qts = 1,351 | Vas = 35,1 L | perdas assumidas: QL = 7 | pequenas amplitudes.',fontsize=10,color='#596579')
fig.subplots_adjust(left=.08,right=.97,top=.86,bottom=.19)
fig.savefig(out/'resposta-unf125.png',facecolor=fig.get_facecolor())
fig.savefig(out/'resposta-unf125.svg',facecolor=fig.get_facecolor())
with (out/'resposta-unf125.csv').open('w',newline='',encoding='utf-8-sig') as fp:
    writer=csv.writer(fp);writer.writerow(['frequencia_hz','db_40L','db_45L','db_50L'])
    writer.writerows(zip(f,lo,mid,hi))
points=[20,25,30,35,40,50,60,80,100,150,200]
print(list(zip(points,np.round(response(points,45),1))))
print('peak',f[i],mid[i])
