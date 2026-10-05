param([switch]$AplicarInicial, [switch]$VerificarPainel)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LFE equalizador comum.ps1')
if ($AplicarInicial) { Save-LfeSettings (Get-LfeSettings) | ConvertTo-Json -Compress; exit }
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()
$script:settings = Get-LfeSettings
$script:sliders = @{}; $script:gainLabels = @{}
$script:hzControls = @{}; $script:qControls = @{}
$form = [Windows.Forms.Form]::new()
$form.Text = 'Equalizador do subwoofer - LFE'
$form.ClientSize = [Drawing.Size]::new(820,850)
$form.StartPosition = 'CenterScreen'; $form.FormBorderStyle = 'FixedDialog'; $form.MaximizeBox = $false
$form.Font = [Drawing.Font]::new('Segoe UI',10)
$form.BackColor = [Drawing.Color]::FromArgb(245,247,250)
$title = [Windows.Forms.Label]::new(); $title.Text = 'Graves do subwoofer'
$title.Font = [Drawing.Font]::new('Segoe UI',21,[Drawing.FontStyle]::Bold); $title.SetBounds(24,16,760,45)
$subtitle = [Windows.Forms.Label]::new(); $subtitle.Text = 'EQ do LFE + graves de SL/SR e CEN | FL/FR 76,8; CEN 5,8; SL/SR 71; LFE 0 ms'; $subtitle.SetBounds(27,66,760,27)
$enabled = [Windows.Forms.CheckBox]::new(); $enabled.Text = 'Equalizador ligado'; $enabled.Checked = $script:settings.Enabled; $enabled.SetBounds(27,106,240,27)
$headroom = [Windows.Forms.CheckBox]::new(); $headroom.Text = 'Margem automatica de volume no LFE'; $headroom.Checked = $script:settings.AutoHeadroom; $headroom.SetBounds(310,106,470,27)
$form.Controls.AddRange(@($title,$subtitle,$enabled,$headroom))
$index = 0
foreach ($frequency in $script:lfeFrequencies) {
    $x = 28 + $index * 86
    $label = [Windows.Forms.Label]::new(); $label.Text = 'Hz'; $label.TextAlign = 'MiddleCenter'; $label.SetBounds($x,143,72,24)
    $hzControl = [Windows.Forms.NumericUpDown]::new(); $hzControl.Minimum=10; $hzControl.Maximum=200
    $hzControl.Value = [decimal]$script:settings.Frequencies.PSObject.Properties["$frequency"].Value
    $hzControl.SetBounds($x,169,72,28); $script:hzControls["$frequency"]=$hzControl
    $slider = [Windows.Forms.TrackBar]::new(); $slider.Orientation = 'Vertical'; $slider.Minimum = -24; $slider.Maximum = 12
    $slider.TickFrequency = 6; $slider.SmallChange = 1; $slider.LargeChange = 2
    $slider.Value = [int]([double]$script:settings.Bands.PSObject.Properties["$frequency"].Value * 2)
    $slider.SetBounds($x+13,208,50,158); $slider.Tag = $frequency
    $gainLabel = [Windows.Forms.Label]::new(); $gainLabel.TextAlign = 'MiddleCenter'; $gainLabel.SetBounds($x-2,368,82,28)
    $qLabel = [Windows.Forms.Label]::new(); $qLabel.Text='Q'; $qLabel.TextAlign='MiddleCenter'; $qLabel.SetBounds($x,400,72,22)
    $qControl = [Windows.Forms.NumericUpDown]::new(); $qControl.Minimum=[decimal]0.3; $qControl.Maximum=10; $qControl.DecimalPlaces=1; $qControl.Increment=[decimal]0.1
    $qControl.Value=[decimal]$script:settings.Widths.PSObject.Properties["$frequency"].Value
    $qControl.SetBounds($x,425,72,28); $script:qControls["$frequency"]=$qControl
    $script:sliders["$frequency"] = $slider; $script:gainLabels["$frequency"] = $gainLabel
    $slider.Add_ValueChanged({ & $script:updateLabels })
    $form.Controls.AddRange(@($label,$hzControl,$slider,$gainLabel,$qLabel,$qControl)); $index++
}
$qTip = [Windows.Forms.Label]::new(); $qTip.Text='Q menor = faixa mais larga | Q maior = ajuste mais estreito'; $qTip.SetBounds(27,469,765,24)
$levelLabel = [Windows.Forms.Label]::new(); $levelLabel.Text='Volume LFE (dB):'; $levelLabel.SetBounds(27,506,155,25)
$level = [Windows.Forms.NumericUpDown]::new(); $level.Minimum=-24; $level.Maximum=0; $level.DecimalPlaces=1; $level.Increment=[decimal]0.5
$level.Value=[decimal]$script:settings.LevelDb; $level.SetBounds(184,503,80,28)
$marginLabel = [Windows.Forms.Label]::new(); $marginLabel.SetBounds(280,506,520,42); $marginLabel.Font=[Drawing.Font]::new('Segoe UI',9)
$bassEnabled = [Windows.Forms.CheckBox]::new(); $bassEnabled.Text='Enviar graves de SL/SR para o sub'; $bassEnabled.Checked=$script:settings.BassEnabled; $bassEnabled.SetBounds(27,556,390,27)
$bassMargin = [Windows.Forms.CheckBox]::new(); $bassMargin.Text='Margem de volume da soma'; $bassMargin.Checked=$script:settings.BassAutoHeadroom; $bassMargin.SetBounds(447,556,335,27)
$cutLabel = [Windows.Forms.Label]::new(); $cutLabel.Text='Corte (Hz):'; $cutLabel.SetBounds(27,600,95,27)
$cut = [Windows.Forms.NumericUpDown]::new(); $cut.Minimum=40; $cut.Maximum=120; $cut.Increment=5; $cut.Value=[decimal]$script:settings.BassCutoffHz; $cut.SetBounds(123,596,75,28)
$sendLabel = [Windows.Forms.Label]::new(); $sendLabel.Text='Envio por surround (dB):'; $sendLabel.SetBounds(235,600,200,27)
$sendControl = [Windows.Forms.NumericUpDown]::new(); $sendControl.Minimum=-24; $sendControl.Maximum=0; $sendControl.DecimalPlaces=1; $sendControl.Increment=[decimal]0.5; $sendControl.Value=[decimal]$script:settings.BassSendDb; $sendControl.SetBounds(440,596,75,28)
$bassTip = [Windows.Forms.Label]::new(); $bassTip.Text='SL/SR: corte ajustavel. CEN: copia abaixo de 120 Hz para o LFE, sem cortar a central.'; $bassTip.Font=[Drawing.Font]::new('Segoe UI',9); $bassTip.SetBounds(27,636,765,25)
$masterLabel = [Windows.Forms.Label]::new(); $masterLabel.Text='Volume mestre - 6 caixas'; $masterLabel.SetBounds(27,682,205,26)
$master = [Windows.Forms.TrackBar]::new(); $master.Minimum=0; $master.Maximum=100; $master.TickFrequency=10; $master.SmallChange=1; $master.LargeChange=5; $master.Value=[int]$script:settings.MasterPercent; $master.SetBounds(240,672,350,45)
$masterValue = [Windows.Forms.Label]::new(); $masterValue.SetBounds(605,682,70,26)
$masterMute = [Windows.Forms.CheckBox]::new(); $masterMute.Text='Mudo'; $masterMute.Checked=$script:settings.MasterMuted; $masterMute.SetBounds(700,680,90,28)
$masterTip = [Windows.Forms.Label]::new(); $masterTip.Text='Volume mestre e Mudo sao aplicados e salvos automaticamente.'; $masterTip.Font=[Drawing.Font]::new('Segoe UI',9); $masterTip.SetBounds(27,718,765,24)
$apply = [Windows.Forms.Button]::new(); $apply.Text = 'Aplicar e salvar'; $apply.SetBounds(27,756,200,42)
$flat = [Windows.Forms.Button]::new(); $flat.Text = 'Zerar bandas'; $flat.SetBounds(242,756,150,42)
$preset = [Windows.Forms.Button]::new(); $preset.Text = '30/40 +3 | 60 -2 dB'; $preset.SetBounds(407,756,200,42)
$statusLabel = [Windows.Forms.Label]::new(); $statusLabel.SetBounds(27,818,765,25); $statusLabel.Font = [Drawing.Font]::new('Segoe UI',9)
$statusLabel.Text = 'Ajuste as bandas e clique em Aplicar. As configuracoes ficam salvas.'
$form.Controls.AddRange(@($qTip,$levelLabel,$level,$marginLabel,$bassEnabled,$bassMargin,$cutLabel,$cut,$sendLabel,$sendControl,$bassTip,$masterLabel,$master,$masterValue,$masterMute,$masterTip,$apply,$flat,$preset,$statusLabel))
$script:readPanel = {
    $bands = [ordered]@{}; $frequencies=[ordered]@{}; $widths=[ordered]@{}
    foreach ($frequency in $script:lfeFrequencies) {
        $bands["$frequency"] = $script:sliders["$frequency"].Value / 2.0
        $frequencies["$frequency"]=[double]$script:hzControls["$frequency"].Value
        $widths["$frequency"]=[double]$script:qControls["$frequency"].Value
    }
    [pscustomobject]@{Enabled=[bool]$enabled.Checked;AutoHeadroom=[bool]$headroom.Checked;Bands=[pscustomobject]$bands;Frequencies=[pscustomobject]$frequencies;Widths=[pscustomobject]$widths;LevelDb=[double]$level.Value;BassEnabled=[bool]$bassEnabled.Checked;BassAutoHeadroom=[bool]$bassMargin.Checked;BassCutoffHz=[double]$cut.Value;BassSendDb=[double]$sendControl.Value;MasterPercent=[int]$master.Value;MasterMuted=[bool]$masterMute.Checked}
}
$script:updateLabels = {
    foreach ($frequency in $script:lfeFrequencies) {
        $script:gainLabels["$frequency"].Text = ('{0:+0.0;-0.0;0.0} dB' -f ($script:sliders["$frequency"].Value / 2.0))
    }
    $marginSettings = & $script:readPanel
    $currentCenter = Get-LfeSettings
    foreach ($property in @('CenterBassEnabled','CenterBassCutoffHz','CenterBassSendDb','CenterBassAutoHeadroom')) {
        $marginSettings | Add-Member -NotePropertyName $property -NotePropertyValue $currentCenter.$property
    }
    $margin = Get-LfeHeadroom $marginSettings
    $marginLabel.Text = ('Margem LFE: -{0:0.0} dB. Reforcos relativos a esse nivel.' -f $margin) + "`r`nAlteracoes entram ao clicar em Aplicar."
}
$enabled.Add_CheckedChanged({ & $script:updateLabels }); $headroom.Add_CheckedChanged({ & $script:updateLabels })
$level.Add_ValueChanged({ & $script:updateLabels })
$bassEnabled.Add_CheckedChanged({ & $script:updateLabels }); $bassMargin.Add_CheckedChanged({ & $script:updateLabels }); $sendControl.Add_ValueChanged({ & $script:updateLabels })
$flat.Add_Click({ foreach ($frequency in $script:lfeFrequencies) { $script:sliders["$frequency"].Value = 0 } })
$preset.Add_Click({
    foreach ($frequency in $script:lfeFrequencies) {
        $script:hzControls["$frequency"].Value=$frequency; $script:qControls["$frequency"].Value=2
        $script:sliders["$frequency"].Value = 0
    }
    $script:sliders['30'].Value = 6; $script:sliders['40'].Value = 6; $script:sliders['60'].Value = -4
    $enabled.Checked = $true; $headroom.Checked = $true
    $level.Value=0
})
$apply.Add_Click({
    $apply.Enabled = $false
    try {
        $result = Save-LfeSettings (& $script:readPanel)
        $statusLabel.ForeColor = [Drawing.Color]::ForestGreen
        $statusLabel.Text = if ($result.AppliedLive) { 'Aplicado e salvo. Buffers e atrasos por canal mantidos.' } else { 'Salvo. Sera aplicado quando voce ligar o sistema 5.1.' }
    } catch {
        $statusLabel.ForeColor = [Drawing.Color]::Firebrick; $statusLabel.Text = 'Nao aplicado: ' + $_.Exception.Message
    } finally { $apply.Enabled = $true }
})
& $script:updateLabels
$masterValue.Text = "$($master.Value)%"
$masterTimer = [Windows.Forms.Timer]::new(); $masterTimer.Interval=250
$masterTimer.Add_Tick({
    $masterTimer.Stop()
    try {
        $result = Set-SystemMasterVolume -Percent $master.Value -Muted $masterMute.Checked
        $statusLabel.ForeColor=[Drawing.Color]::ForestGreen
        $statusLabel.Text=if($result.AppliedLive){'Volume mestre aplicado e salvo.'}else{'Volume mestre salvo para a proxima abertura do sistema.'}
    } catch { $statusLabel.ForeColor=[Drawing.Color]::Firebrick; $statusLabel.Text='Volume nao aplicado: '+$_.Exception.Message }
})
$master.Add_ValueChanged({ $masterValue.Text="$($master.Value)%"; $masterTimer.Stop(); $masterTimer.Start() })
$masterMute.Add_CheckedChanged({ $masterTimer.Stop(); $masterTimer.Start() })
$form.Add_FormClosing({
    if($masterTimer.Enabled){$masterTimer.Stop(); try { Set-SystemMasterVolume -Percent $master.Value -Muted $masterMute.Checked | Out-Null } catch {} }
})
$form.Add_FormClosed({ $masterTimer.Stop(); $masterTimer.Dispose() })
if ($VerificarPainel) {
    # Offscreen render of the form we authored; no screen capture or UI automation.
    $form.Show()
    [Windows.Forms.Application]::DoEvents()
    $bitmap = [Drawing.Bitmap]::new($form.Width,$form.Height)
    $form.DrawToBitmap($bitmap,[Drawing.Rectangle]::new(0,0,$form.Width,$form.Height))
    $bitmap.Save((Join-Path $PSScriptRoot 'equalizador-lfe-painel.png'),[Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose(); $form.Dispose(); Write-Output 'Painel criado e renderizado.'; exit
}
[void]$form.ShowDialog()
$form.Dispose()
