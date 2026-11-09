; Texte des Installers, deutsch und englisch (eingebunden von BaumeisterHost.iss).
; UTF-8 ohne BOM (Inno Setup ab 6.3), echte Umlaute.

[CustomMessages]
de.LaunchApp=Baumeister-Host starten
en.LaunchApp=Start Baumeister-Host
de.AutostartTask=Beim Anmelden automatisch starten
en.AutostartTask=Start automatically at sign-in
de.PuttyMissing=PuTTY (plink.exe) wurde unter %1 nicht gefunden.%n%nFür den Tunnel zum Minecraft-Server braucht der Baumeister-Host PuTTY (Pageant mit Schlüssel oder ein Passwort). Bitte PuTTY installieren (putty.org) oder den Pfad später in den Einstellungen der App eintragen.
en.PuttyMissing=PuTTY (plink.exe) was not found at %1.%n%nThe tunnel to the Minecraft server needs PuTTY (Pageant with a key, or a password). Please install PuTTY (putty.org) or set the path later in the app settings.

; Du statt Sie (Wunsch 2026-11-04). German.isl aus Inno Setup 6.7.3
; siezt an 64 Stellen; hier dieselben Schlüssel in der Du-Form, alle übrigen Texte kommen unverändert aus German.isl.
; Wer Inno Setup hochstuft, sucht in der neuen German.isl nach „Sie“/„Ihr“: Neu hinzugekommene Schlüssel siezen
; sonst wieder, weil nur die hier stehenden überschrieben werden.
[Messages]
de.SetupLdrStartupMessage=%1 wird jetzt installiert. Möchtest du fortfahren?
de.SetupFileMissing=Die Datei %1 fehlt im Installationsordner. Bitte behebe das Problem oder besorg dir eine neue Kopie des Programms.
de.SetupFileCorrupt=Die Setup-Dateien sind beschädigt. Besorg dir bitte eine neue Kopie des Programms.
de.SetupFileCorruptOrWrongVer=Die Setup-Dateien sind beschädigt oder inkompatibel zu dieser Version des Setups. Bitte behebe das Problem oder besorg dir eine neue Kopie des Programms.
de.WindowsVersionNotSupported=Dieses Programm unterstützt die auf deinem Computer installierte Windows-Version nicht.
de.AdminPrivilegesRequired=Du musst als Administrator angemeldet sein, um dieses Programm installieren zu können.
de.PowerUserPrivilegesRequired=Du musst als Administrator oder als Mitglied der Hauptbenutzer-Gruppe angemeldet sein, um dieses Programm installieren zu können.
de.SetupAppRunningError=Das Setup hat entdeckt, dass %1 zurzeit ausgeführt wird.%n%nBitte schließe jetzt alle laufenden Instanzen und klicke auf "OK", um fortzufahren, oder auf "Abbrechen", um zu beenden.
de.UninstallAppRunningError=Die Deinstallation hat entdeckt, dass %1 zurzeit ausgeführt wird.%n%nBitte schließe jetzt alle laufenden Instanzen und klicke auf "OK", um fortzufahren, oder auf "Abbrechen", um zu beenden.
de.PrivilegesRequiredOverrideInstruction=Bitte wähle den Installationsmodus
de.PrivilegesRequiredOverrideText1=%1 kann für alle Benutzer (erfordert Administrationsrechte) oder nur für dich installiert werden.
de.PrivilegesRequiredOverrideText2=%1 kann nur für dich oder für alle Benutzer (erfordert Administrationsrechte) installiert werden.
de.PrivilegesRequiredOverrideCurrentUser=Installation nur für &dich
de.PrivilegesRequiredOverrideCurrentUserRecommended=Installation nur für &dich (empfohlen)
de.ExitSetupMessage=Das Setup ist noch nicht abgeschlossen. Wenn du jetzt beendest, wird das Programm nicht installiert.%n%nDu kannst das Setup zu einem späteren Zeitpunkt nochmals ausführen, um die Installation zu vervollständigen.%n%nSetup verlassen?
de.SelectLanguageLabel=Wähle die Sprache aus, die während der Installation benutzt werden soll:
de.BrowseDialogLabel=Wähle einen Ordner aus und klicke danach auf "OK".
de.WelcomeLabel2=Dieser Assistent wird jetzt [name/ver] auf deinem Computer installieren.%n%nDu solltest alle anderen Anwendungen beenden, bevor du mit dem Setup fortfährst.
de.PasswordLabel3=Bitte gib das Passwort ein und klicke danach auf "Weiter". Achte auf korrekte Groß-/Kleinschreibung.
de.IncorrectPassword=Das eingegebene Passwort ist nicht korrekt. Bitte versuch es noch einmal.
de.LicenseLabel=Lies bitte folgende wichtige Informationen, bevor du fortfährst.
de.LicenseLabel3=Lies bitte die folgenden Lizenzvereinbarungen. Benutze bei Bedarf die Bildlaufleiste oder drücke die "Bild Ab"-Taste.
de.InfoBeforeLabel=Lies bitte folgende wichtige Informationen, bevor du fortfährst.
de.InfoBeforeClickLabel=Klicke auf "Weiter", sobald du bereit bist, mit dem Setup fortzufahren.
de.InfoAfterLabel=Lies bitte folgende wichtige Informationen, bevor du fortfährst.
de.InfoAfterClickLabel=Klicke auf "Weiter", sobald du bereit bist, mit dem Setup fortzufahren.
de.UserInfoDesc=Bitte trag deine Daten ein.
de.UserInfoNameRequired=Du musst einen Namen eintragen.
de.SelectDirBrowseLabel=Klicke auf "Weiter", um fortzufahren. Klicke auf "Durchsuchen", falls du einen anderen Ordner auswählen möchtest.
de.CannotInstallToUNCPath=Das Setup kann nicht in einen UNC-Pfad installieren. Wenn du auf ein Netzlaufwerk installieren möchtest, musst du dem Netzwerkpfad einen Laufwerksbuchstaben zuordnen.
de.InvalidPath=Du musst einen vollständigen Pfad mit einem Laufwerksbuchstaben angeben, z. B.:%n%nC:\Beispiel%n%noder einen UNC-Pfad in der Form:%n%n\\Server\Freigabe
de.InvalidDrive=Das angegebene Laufwerk bzw. der UNC-Pfad existiert nicht oder es kann nicht darauf zugegriffen werden. Wähle bitte einen anderen Ordner.
de.DiskSpaceWarning=Das Setup benötigt mindestens %1 KB freien Speicherplatz zum Installieren, aber auf dem ausgewählten Laufwerk sind nur %2 KB verfügbar.%n%nMöchtest du trotzdem fortfahren?
de.DirExists=Der Ordner:%n%n%1%n%n existiert bereits. Möchtest du trotzdem in diesen Ordner installieren?
de.SelectComponentsLabel2=Wähle die Komponenten aus, die du installieren möchtest. Klicke auf "Weiter", wenn du bereit bist, fortzufahren.
de.NoUninstallWarning=Das Setup hat festgestellt, dass die folgenden Komponenten bereits auf deinem Computer installiert sind:%n%n%1%n%nDiese nicht mehr ausgewählten Komponenten werden nicht vom Computer entfernt.%n%nMöchtest du trotzdem fortfahren?
de.SelectTasksLabel2=Wähle die zusätzlichen Aufgaben aus, die das Setup während der Installation von [name] ausführen soll, und klicke danach auf "Weiter".
de.SelectStartMenuFolderBrowseLabel=Klicke auf "Weiter", um fortzufahren. Klicke auf "Durchsuchen", falls du einen anderen Ordner auswählen möchtest.
de.MustEnterGroupName=Du musst einen Ordnernamen eingeben.
de.ReadyLabel1=Das Setup ist jetzt bereit, [name] auf deinem Computer zu installieren.
de.ReadyLabel2a=Klicke auf "Installieren", um mit der Installation zu beginnen, oder auf "Zurück", um deine Einstellungen zu überprüfen oder zu ändern.
de.ReadyLabel2b=Klicke auf "Installieren", um mit der Installation zu beginnen.
de.StopDownload=Bist du sicher, dass du den Download abbrechen willst?
de.StopExtraction=Bist du sicher, dass du das Entpacken abbrechen willst?
de.PreviousInstallNotCompleted=Eine vorherige Installation/Deinstallation eines Programms wurde nicht abgeschlossen. Der Computer muss neu gestartet werden, um die Installation/Deinstallation zu beenden.%n%nStarte das Setup nach dem Neustart deines Computers erneut, um die Installation von [name] durchzuführen.
de.CannotContinue=Das Setup kann nicht fortfahren. Bitte klicke auf "Abbrechen" zum Verlassen.
de.PrepareToInstallNeedsRestart=Das Setup muss deinen Computer neu starten. Führe nach dem Neustart Setup erneut aus, um die Installation von [name] abzuschließen.%n%nWillst du jetzt neu starten? 
de.InstallingLabel=Warte bitte, während [name] auf deinem Computer installiert wird.
de.FinishedLabelNoIcons=Das Setup hat die Installation von [name] auf deinem Computer abgeschlossen.
de.FinishedLabel=Das Setup hat die Installation von [name] auf deinem Computer abgeschlossen. Die Anwendung kann über die installierten Programm-Verknüpfungen gestartet werden.
de.ClickFinish=Klicke auf "Fertigstellen", um das Setup zu beenden.
de.FinishedRestartLabel=Um die Installation von [name] abzuschließen, muss das Setup deinen Computer neu starten. Möchtest du jetzt neu starten?
de.FinishedRestartMessage=Um die Installation von [name] abzuschließen, muss das Setup deinen Computer neu starten.%n%nMöchtest du jetzt neu starten?
de.SelectDiskLabel2=Leg bitte Datenträger %1 ein und klicke auf "OK".%n%nWenn sich die Dateien von diesem Datenträger in einem anderen als dem angezeigten Ordner befinden, dann gib bitte den korrekten Pfad ein oder klicke auf "Durchsuchen".
de.SelectDirectoryLabel=Gib bitte an, wo der nächste Datenträger eingelegt wird.
de.SetupAborted=Das Setup konnte nicht abgeschlossen werden.%n%nBehebe bitte das Problem und starte das Setup erneut.
de.ErrorRestartingComputer=Das Setup konnte den Computer nicht neu starten. Bitte führe den Neustart manuell durch.
de.ConfirmUninstall=Bist du sicher, dass du %1 und alle zugehörigen Komponenten entfernen möchtest?
de.UninstallStatusLabel=Warte bitte, während %1 von deinem Computer entfernt wird.
de.UninstalledAll=%1 wurde erfolgreich von deinem Computer entfernt.
de.UninstalledMost=Entfernen von %1 beendet.%n%nEinige Komponenten konnten nicht entfernt werden. Diese kannst du manuell löschen.
de.UninstalledAndNeedsRestart=Um die Deinstallation von %1 abzuschließen, muss dein Computer neu gestartet werden.%n%nMöchtest du jetzt neu starten?
de.ConfirmDeleteSharedFile2=Das System zeigt an, dass die folgende gemeinsame Datei von keinem anderen Programm mehr benutzt wird. Möchtest du diese Datei entfernen lassen?%nSollte es doch noch Programme geben, die diese Datei benutzen und sie wird entfernt, funktionieren diese Programme vielleicht nicht mehr richtig. Wenn du unsicher bist, wähle "Nein", um die Datei im System zu belassen. Es schadet deinem System nicht, wenn du die Datei behältst.
de.AddonHostProgramNotFound=%1 konnte im ausgewählten Ordner nicht gefunden werden.%n%nMöchtest du dennoch fortfahren?
