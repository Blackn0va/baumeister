; [Code] des Baumeister-Host-Setups (eingebunden von BaumeisterHost.iss).
;
; Nur ein Hinweis: Fehlt PuTTY am Standardort, sagt das Setup es am Ende – sonst stünde der Tunnel nach dem ersten
; Start auf „Fehler“, ohne dass man weiß warum. Kein Abbruch: plink kann auch woanders liegen (Einstellungen der App).

[Code]
procedure CurStepChanged(CurStep: TSetupStep);
var
  Plink: String;
begin
  if CurStep = ssPostInstall then
  begin
    Plink := ExpandConstant('{autopf}\PuTTY\plink.exe');
    if (not FileExists(Plink)) and (not WizardSilent) then
      MsgBox(FmtMessage(CustomMessage('PuttyMissing'), [Plink]), mbInformation, MB_OK);
  end;
end;
