import runpy
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import csv
out=Path(__file__).parent
m=runpy.run_path(str(out/'comparar_sala.py'))
f=m['f']; response=m['response'];sealed=m['sealed'];room=m['gains'][0]
def target(f):
 return -10*np.log10(1+(25/np.asarray(f))**8)
dut=response(f,45)-response(80,45)
sel=sealed(f,40)-sealed(80,40)
ht=target(f)-target(80)
plt.close('all')
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':11})
fig,axes=plt.subplots(2,1,figsize=(12,10),dpi=150,sharex=True)
fig.patch.set_facecolor('#f5f7fb')
colors=['#1769aa','#e27c28','#168665']
labels=['UNF125: dutada equivalente 45 L / 35 Hz','UNF125: selada estimada em 40 L','Referência home theater: −3 dB em 25 Hz']
for ax,rg,title in zip(axes,[np.zeros_like(f),room],['Comparação da extensão: sem a sala','Mesmas caixas na sala: escuta no centro (hipótese)']):
 ax.set_facecolor('white')
 for y,col,label in zip([dut,sel,ht],colors,labels):
  ax.semilogx(f,y+rg,lw=2.8,color=col,label=label)
 ax.axhline(0,color='#64748b',lw=.8)
 ax.axhline(-3,color='#8d98a8',lw=.8,ls=':')
 ax.axvline(25,color='#168665',lw=.9,ls=':')
 ax.set_xlim(15,120);ax.set_ylim(-40,15);ax.minorticks_off();ax.grid(True,color='#e4e8ef')
 ax.set_ylabel('Resposta relativa (dB)');ax.set_title(title,loc='left',fontweight='bold');ax.legend(loc='lower right',fontsize=9)
axes[0].annotate('Alvo ilustrativo: extensão até 25 Hz',xy=(25,float(target(25)-target(80))),xytext=(32,-10),arrowprops={'arrowstyle':'->','color':'#168665'},color='#168665',fontsize=10)
ticks=[15,20,25,30,35,40,50,60,80,100,120]
axes[-1].set_xticks(ticks);axes[-1].set_xticklabels([str(t) for t in ticks]);axes[-1].set_xlabel('Frequência (Hz)')
fig.suptitle('UNF125 × referência de subwoofer para home theater',x=.08,ha='left',fontsize=19,fontweight='bold',y=.975)
fig.text(.08,.94,'Curvas igualadas em 80 Hz antes de aplicar a sala: comparação de extensão, sem prever volume máximo.',fontsize=10,color='#596579')
notes='Referência escolhida: filtro passa-altas Butterworth de 4ª ordem, corte em 25 Hz; sem passa-baixas.\nÉ um alvo de comparação, não uma média estatística nem a curva medida de um produto.\nSala: 3 × 6 × 2,5 m; sub junto à parede longa a 2,5 m da ponta; ouvido no centro a 1,10 m; RT60 = 0,5 s assumido.\nContribuição da sala normalizada pela média de 120–180 Hz. Volumes e perdas das caixas ainda estimados.'
fig.text(.08,.045,notes,fontsize=9,color='#596579')
fig.subplots_adjust(left=.08,right=.97,top=.90,bottom=.18,hspace=.28)
fig.savefig(out/'comparacao-home-theater.png',facecolor=fig.get_facecolor())
fig.savefig(out/'comparacao-home-theater.svg',facecolor=fig.get_facecolor())
with (out/'comparacao-home-theater.csv').open('w',newline='',encoding='utf-8-sig') as fp:
 writer=csv.writer(fp);writer.writerow(['frequencia_hz','dutada_db','selada_db','alvo_ht_db','dutada_sala_db','selada_sala_db','alvo_ht_sala_db']);writer.writerows(zip(f,dut,sel,ht,dut+room,sel+room,ht+room))
for freq in [20,25,30,35,60,80]:
 print(freq,round(float(response(freq,45)-response(80,45)),1),round(float(sealed(freq,40)-sealed(80,40)),1),round(float(target(freq)-target(80)),1))
