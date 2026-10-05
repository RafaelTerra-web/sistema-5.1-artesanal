param([int]$Port=8787)
$ErrorActionPreference='Stop'
# Exercise the real HTTP -> worker -> Win32 path using only windows owned by this test.
$testDir=Join-Path ([IO.Path]::GetTempPath()) ('remote51-input-'+[guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($testDir) | Out-Null
$report=Join-Path $testDir 'result.txt'
$executable=Join-Path $testDir 'Remote51InputTest.exe'
$source=@'
using System;
using System.IO;
using System.Text;
using System.Windows.Forms;
using System.Drawing;
using System.Runtime.InteropServices;
public class Remote51InputTest {
 [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr hwnd,int command);
 [STAThread] public static void Main(string[] args) {
  string report=args[0];int clicks=0;
  Application.EnableVisualStyles();
  Form target=new Form {Text="Teste controle universal - alvo",StartPosition=FormStartPosition.Manual,Location=new Point(100,100),ClientSize=new Size(480,300)};
  TextBox text=new TextBox {Location=new Point(20,25),Size=new Size(400,30),TabIndex=0};
  Button button=new Button {Text="Botao de teste",Location=new Point(120,85),Size=new Size(240,110),TabIndex=1};
  target.Controls.Add(text);target.Controls.Add(button);
  Form other=new Form {Text="Teste controle universal - outra janela",StartPosition=FormStartPosition.Manual,Location=new Point(700,100),ClientSize=new Size(320,200)};
  TextBox otherText=new TextBox {Location=new Point(20,25),Size=new Size(280,30)};other.Controls.Add(otherText);
  Action save=()=>{try {File.WriteAllLines(report,new string[]{Convert.ToBase64String(Encoding.UTF8.GetBytes(text.Text)),clicks.ToString(),target.ContainsFocus.ToString(),otherText.Text},new UTF8Encoding(false));}catch(IOException){}};
  button.Click+=(s,e)=>{clicks++;save();};text.TextChanged+=(s,e)=>save();
  bool initialized=false;
  Timer timer=new Timer {Interval=150};timer.Tick+=(s,e)=>{if(!initialized){initialized=true;ShowWindow(target.Handle,5);text.Focus();other.Show();ShowWindow(other.Handle,5);other.Activate();otherText.Focus();}save();};timer.Start();
  target.FormClosed+=(s,e)=>other.Close();
  Application.Run(target);
 }
}
'@
Add-Type -TypeDefinition $source -ReferencedAssemblies 'System.Windows.Forms','System.Drawing' -OutputAssembly $executable -OutputType WindowsApplication
$process=$null
try {
    $connection=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'connection-private.json') -Raw | ConvertFrom-Json
    $origin='http://127.0.0.1:'+$Port
    $session=New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $headers=@{Origin=$origin}
    Invoke-RestMethod ($origin+'/api/pair') -Method Post -ContentType 'application/json' -Body (@{pin=$connection.pin}|ConvertTo-Json) -WebSession $session -Headers $headers | Out-Null
    $process=Start-Process -FilePath $executable -ArgumentList ('"'+$report+'"') -WindowStyle Hidden -PassThru
    $window=$null
    for($i=0;$i -lt 30 -and -not $window;$i++) {
        $status=Invoke-RestMethod ($origin+'/api/status') -WebSession $session
        $window=$status.windows | Where-Object { $_.title -eq 'Teste controle universal - alvo' -and $_.app -eq 'Remote51InputTest' } | Select-Object -First 1
        if(-not $window){Start-Sleep -Milliseconds 200}
    }
    if(-not $window){throw 'A janela de teste nao foi encontrada.'}
    function Send-TestInput([string]$Action,[string]$Mode='keyboard',[hashtable]$Extra=@{}) {
        $body=@{window=$window.id;action=$Action;mode=$Mode}+$Extra
        try { Invoke-RestMethod ($origin+'/api/input') -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes(($body|ConvertTo-Json))) -WebSession $session -Headers $headers | Out-Null }
        catch { if($_.Exception.Response){$reader=[IO.StreamReader]::new($_.Exception.Response.GetResponseStream());throw ($Action+': '+$reader.ReadToEnd())};throw }
        Start-Sleep -Milliseconds 200
    }
    $expectedText='Teste a'+[char]0xe7+[char]0xe3+'o 5.1'
    Send-TestInput 'text' 'keyboard' @{text=$expectedText}
    Send-TestInput 'tab'
    Send-TestInput 'select'
    # Reset the pointer using this owned window's actual rectangle before clicking its center button.
    Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;
public static class Remote51TestCursor {[DllImport("user32.dll")]public static extern bool SetCursorPos(int x,int y);}
'@
    [Remote51TestCursor]::SetCursorPos(0,0) | Out-Null
    Send-TestInput 'move' 'mouse' @{dx=0;dy=0}
    Send-TestInput 'select' 'mouse'
    $result=[IO.File]::ReadAllLines($report)
    $typed=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($result[0]))
    if($typed -ne $expectedText){throw ('Texto inesperado na janela de teste: '+$typed)}
    if([int]$result[1] -ne 2){throw ('Cliques inesperados: '+$result[1])}
    if($result[2] -ne 'True' -or $result[3]){throw 'O foco ou texto atingiu a janela errada.'}
    # A closed or stale window must never fall back to a different foreground application.
    $process.CloseMainWindow() | Out-Null
    if(-not $process.WaitForExit(3000)){throw 'A janela de teste nao encerrou.'}
    $rejected=$false
    try {Send-TestInput 'tab'}catch{$rejected=$true}
    if(-not $rejected){throw 'Um identificador de janela encerrada foi aceito.'}
    [pscustomobject]@{TecladoUnicode=$true;TabEnter=$true;CliqueMouse=$true;FocoCorreto=$true;JanelaEncerradaRejeitada=$true} | ConvertTo-Json -Compress
} finally {
    if($process -and -not $process.HasExited){Stop-Process -Id $process.Id -ErrorAction SilentlyContinue}
}
