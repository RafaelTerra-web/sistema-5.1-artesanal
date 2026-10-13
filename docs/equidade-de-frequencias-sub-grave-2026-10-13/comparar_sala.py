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

L=np.array([3.,6.,2.5]); source=np.array([.20,2.5,.25]); c=343.; rt60=.5
listeners=[np.array([1.5,3.,1.1]),np.array([2.4,3.,1.1])]
def room_transfer(freq,receiver,cutoff=1400):
    limits=np.ceil(2*cutoff*L/c).astype(int)
    modes=np.array(np.meshgrid(*[np.arange(i+1) for i in limits],indexing='ij')).reshape(3,-1).T
    fn=c/2*np.sqrt(np.sum((modes/L)**2,axis=1))
    keep=fn<=cutoff; modes=modes[keep];fn=fn[keep]
    weights=np.prod(np.where(modes==0,1.,2.)*np.cos(np.pi*modes*source/L)*np.cos(np.pi*modes*receiver/L),axis=1)
    wn=2*np.pi*fn; alpha=6.907755/rt60
    result=[]
    for freq_chunk in np.array_split(freq,40):
        w=2*np.pi*freq_chunk
        denominator=wn[:,None]**2-w[None,:]**2+2j*alpha*w[None,:]
        result.extend(4*np.pi*c**2/np.prod(L)*np.sum(weights[:,None]/denominator,axis=0))
    return np.array(result)
gains=[20*np.log10(np.abs(room_transfer(f,r))) for r in listeners]
reference=np.mean(gains[0][(f>=120)&(f<=180)])
gains=[g-reference for g in gains]
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':11})
fig,axes=plt.subplots(2,1,figsize=(12,10),dpi=150,sharex=True,sharey=True)
fig.patch.set_facecolor('#f5f7fb')
basevent=response(f,45); baseseal=sealed(f,40)
for ax,g,title in zip(axes,gains,['Hipótese A: escuta no centro da sala','Hipótese B: escuta a 60 cm da parede oposta ao sub']):
    ax.set_facecolor('white')
    ax.semilogx(f,basevent+g,color='#1769aa',lw=2.5,label='Caixa anterior + sala (45 L / 35 Hz)')
    ax.semilogx(f,baseseal+g,color='#e27c28',lw=2.5,label='Caixa selada + sala (40 L)')
    ax.semilogx(f,basevent,color='#1769aa',lw=1,ls='--',alpha=.5,label='Anterior sem sala')
    ax.semilogx(f,baseseal,color='#e27c28',lw=1,ls='--',alpha=.5,label='Selada sem sala')
    for hz in [28.58,57.17,68.6]:
        ax.axvline(hz,color='#8d98a8',ls=':',lw=1)
    ax.axhline(0,color='#64748b',lw=.8)
    ax.set_title(title,loc='left',fontweight='bold');ax.set_ylabel('Nível relativo (dB)')
    ax.grid(True,color='#e4e8ef');ax.set_xlim(15,200);ax.set_ylim(-35,25)
    ax.minorticks_off();ax.legend(loc='lower right',fontsize=8.5)
ticks=[15,20,25,30,35,40,50,60,80,100,150,200]
axes[-1].set_xticks(ticks);axes[-1].set_xticklabels([str(t) for t in ticks]);axes[-1].set_xlabel('Frequência (Hz)')
fig.suptitle('UNF125: comparação em uma sala de 3 × 6 × 2,5 m',x=.08,ha='left',fontsize=19,fontweight='bold',y=.975)
fig.text(.08,.94,'CENÁRIOS ILUSTRATIVOS • fonte junto à parede longa, a 2,5 m de uma ponta • sala retangular idealizada',fontsize=10,color='#596579')
fig.text(.08,.06,'Hipóteses: centro acústico do sub a 20 cm da parede / 25 cm do chão; ouvido a 1,10 m; RT60 = 0,5 s assumido.\nModelo modal com perdas uniformes; móveis, portas e janelas não modelados. Não é previsão medida de SPL.\nReferência comum: contribuição da sala no centro normalizada pela média de 120–180 Hz.',fontsize=9,color='#596579')
fig.subplots_adjust(left=.08,right=.97,top=.9,bottom=.16,hspace=.25)
fig.savefig(out/'comparacao-na-sala.png',facecolor=fig.get_facecolor())
fig.savefig(out/'comparacao-na-sala.svg',facecolor=fig.get_facecolor())
with (out/'comparacao-na-sala.csv').open('w',newline='',encoding='utf-8-sig') as fp:
    writer=csv.writer(fp);writer.writerow(['frequencia_hz','dutada_centro_db','selada_centro_db','dutada_parede_oposta_db','selada_parede_oposta_db'])
    writer.writerows(zip(f,basevent+gains[0],baseseal+gains[0],basevent+gains[1],baseseal+gains[1]))
print('room gain range',[ (float(g.min()),float(g.max())) for g in gains])
print('modes',c/12,c/6,c/5)
