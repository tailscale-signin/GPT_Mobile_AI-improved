#ifndef GatewayVersion
  #error GatewayVersion must be supplied by the build
#endif
[Setup]
AppId={{01E574CD-9954-40C4-A38D-051592D8A515}
AppName=GPT Mobile Gateway
AppVersion={#GatewayVersion}
AppPublisher=GPT Mobile contributors
DefaultDirName={localappdata}\Programs\GPTMobileGateway
DefaultGroupName=GPT Mobile Gateway
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
WizardStyle=modern dark
#if Ver >= EncodeVer(6, 7, 0)
WizardBackColor=#001219
#endif
WizardSmallImageFile=assets\gateway.png
WizardSmallImageBackColor=#001219
WizardImageFile=..\..\dist\windows\setup-sidebar.png
WizardImageBackColor=#001219
DisableProgramGroupPage=yes
DisableDirPage=no
DisableReadyPage=yes
WizardSizePercent=115
ShowTasksTreeLines=no
OutputDir=..\..\dist\windows
OutputBaseFilename=Gateway-{#GatewayVersion}-windows-x64-setup
Compression=lzma2
SolidCompression=yes
CloseApplications=no
RestartApplications=no
UninstallDisplayIcon={app}\releases\{#GatewayVersion}\Gateway.exe
SetupLogging=yes
SetupIconFile=assets\gateway.ico

[Files]
Source: "..\..\dist\windows\Gateway\*"; DestDir: "{app}\releases\{#GatewayVersion}"; Flags: recursesubdirs createallsubdirs ignoreversion
Source: "Start-Gateway.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "README.md"; DestDir: "{app}"; Flags: ignoreversion

[Tasks]
Name: "startup"; Description: "Start Gateway when I sign in"; Flags: checkedonce; Check: NeedsInitialSettings
Name: "desktopicon"; Description: "Add a desktop shortcut"; Flags: unchecked
Name: "firewall"; Description: "Allow phone connections on this PC's private network (Windows may ask for approval)"; Flags: checkedonce; Check: UsesPhoneNetwork

[Icons]
Name: "{group}\Gateway Dashboard"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File ""{app}\Start-Gateway.ps1"" -Command dashboard"; WorkingDir: "{app}"; IconFilename: "{app}\releases\{#GatewayVersion}\Gateway.exe"; AppUserModelID: "GPTMobile.Gateway"
Name: "{autodesktop}\GPT Mobile Gateway"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File ""{app}\Start-Gateway.ps1"" -Command dashboard"; WorkingDir: "{app}"; IconFilename: "{app}\releases\{#GatewayVersion}\Gateway.exe"; AppUserModelID: "GPTMobile.Gateway"; Tasks: desktopicon
Name: "{group}\Start Gateway"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File ""{app}\Start-Gateway.ps1"""; WorkingDir: "{app}"
Name: "{group}\Gateway Settings"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File ""{app}\Start-Gateway.ps1"" -Command settings"; WorkingDir: "{app}"
Name: "{group}\Coding Benchmark Setup"; Filename: "{app}\releases\{#GatewayVersion}\Gateway.exe"; Parameters: "benchmark-settings --root ""{app}"""; WorkingDir: "{app}"
Name: "{group}\Engineering Setup"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File ""{app}\Start-Gateway.ps1"" -Command engineering-setup"; WorkingDir: "{app}"
Name: "{group}\Gateway Logs"; Filename: "{app}\logs"
Name: "{group}\Uninstall Gateway"; Filename: "{uninstallexe}"

[Run]
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File ""{app}\Start-Gateway.ps1"" -Command dashboard"; Description: "Open Gateway"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: files; Name: "{app}\current.json"
Type: filesandordirs; Name: "{app}\releases"

[Code]
var
  ConnectionPage, AdvancedPage: TInputQueryWizardPage;
  ModePage, UpdatePage: TInputOptionWizardPage;
  RuntimePrepared, InstallFirewall, RepairDamaged, ExistingInstallation: Boolean;

function J(Value: String): String;
begin
  StringChangeEx(Value, '\', '\\', True);
  StringChangeEx(Value, '"', '\"', True);
  StringChangeEx(Value, #13, '\r', True);
  StringChangeEx(Value, #10, '\n', True);
  StringChangeEx(Value, #9, '\t', True);
  Result := '"' + Value + '"';
end;

function B(Value: Boolean): String;
begin
  if Value then Result := 'true' else Result := 'false';
end;

function NeedsInitialSettings: Boolean;
begin
  Result := not FileExists(ExpandConstant('{app}\settings.json'));
end;

function UsesPhoneNetwork: Boolean;
begin
  Result := NeedsInitialSettings and (ModePage.SelectedValueIndex <> 1);
end;

procedure InitializeWizard;
begin
  ModePage := CreateInputOptionPage(wpSelectDir, 'Connect to Gateway',
    'Choose where you will use this PC.', 'Use the Gateway address in GPT Mobile for chat, saved jobs and Engineering. Setup finds your Tailscale or private network and uses the local model server on port 8080.', True, False);
  ModePage.Add('My phone and this PC');
  ModePage.Add('Only this PC');
  ModePage.Add('Advanced settings');
  if WizardSilent then ModePage.SelectedValueIndex := 1 else ModePage.SelectedValueIndex := 0;
  ConnectionPage := CreateInputQueryPage(ModePage.ID, 'Connection settings',
    'Connect a model and choose a network interface.', 'Use auto to find a private interface. Choose 127.0.0.1 for local use.');
  ConnectionPage.Add('Model backend origin', False);
  ConnectionPage.Add('Gateway port (1024-65535)', False);
  ConnectionPage.Add('Listen address', False);
  ConnectionPage.Add('MCP configuration file (optional)', False);
  ConnectionPage.Values[0] := 'http://127.0.0.1:8080';
  ConnectionPage.Values[1] := '8090';
  ConnectionPage.Values[2] := 'auto';
  AdvancedPage := CreateInputQueryPage(ConnectionPage.ID, 'Capacity',
    'Set limits for this PC.', 'You can change worker and chat limits from your connected phone.');
  AdvancedPage.Add('Concurrent workers (1-16)', False);
  AdvancedPage.Add('Active chats (workers-64)', False);
  AdvancedPage.Add('Shutdown grace in seconds (10-120)', False);
  AdvancedPage.Values[0] := '2';
  AdvancedPage.Values[1] := '8';
  AdvancedPage.Values[2] := '30';
  UpdatePage := CreateInputOptionPage(AdvancedPage.ID, 'Stable updates', 'Choose when to update Gateway.',
    'Automatic installation waits for active work to finish.', True, False);
  UpdatePage.Add('Notify me when an update is available');
  UpdatePage.Add('Install automatically when idle');
  UpdatePage.Add('Check manually');
  UpdatePage.SelectedValueIndex := 0;
  WizardForm.Font.Name := 'Segoe UI';
  if not HighContrastActive then begin
    WizardForm.MainPanel.Color := $28200B;
    WizardForm.PageNameLabel.Font.Color := $DEFF00;
    WizardForm.PageDescriptionLabel.Font.Color := $F7F4E0;
    WizardForm.WelcomeLabel1.Font.Color := $DEFF00;
    WizardForm.FinishedHeadingLabel.Font.Color := $DEFF00;
  end;
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  if PageID = ModePage.ID then Result := not NeedsInitialSettings
  else if (PageID = ConnectionPage.ID) or (PageID = AdvancedPage.ID) or (PageID = UpdatePage.ID) then
    Result := (ModePage.SelectedValueIndex <> 2) or not NeedsInitialSettings
  else Result := False;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var P, W, A, G: Integer;
begin
  Result := True;
  if CurPageID = ConnectionPage.ID then begin
    P := StrToIntDef(ConnectionPage.Values[1], 0);
    Result := (P >= 1024) and (P <= 65535) and (Trim(ConnectionPage.Values[0]) <> '') and
       (Trim(ConnectionPage.Values[2]) <> '') and ((ConnectionPage.Values[3] = '') or FileExists(ConnectionPage.Values[3]));
    if not Result then MsgBox('Check the model address, port and optional MCP file.', mbError, MB_OK);
  end;
  if CurPageID = AdvancedPage.ID then begin
    W := StrToIntDef(AdvancedPage.Values[0], 0); A := StrToIntDef(AdvancedPage.Values[1], 0);
    G := StrToIntDef(AdvancedPage.Values[2], 0);
    Result := (W >= 1) and (W <= 16) and (A >= W) and (A <= 64) and (G >= 10) and (G <= 120);
    if not Result then MsgBox('Check the capacity ranges. Each worker needs an active chat slot.', mbError, MB_OK);
  end;
end;

function StopInstalled(Root: String): Boolean;
var Code: Integer;
begin
  Result := True;
  if FileExists(Root + '\current.json') then
    Result := Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'), '-NoProfile -ExecutionPolicy Bypass -File "' + Root + '\Start-Gateway.ps1" -Command stop', Root, SW_HIDE, ewWaitUntilTerminated, Code) and (Code = 0);
end;

function WriteWizardSettings: String;
var Settings, Mode, TempFile, BindAddress: String; Lines: TArrayOfString;
begin
    Settings := '{"start_at_login":' + B(WizardIsTaskSelected('startup'));
    if ModePage.SelectedValueIndex = 2 then begin
      Mode := 'notify'; if UpdatePage.SelectedValueIndex = 1 then Mode := 'auto'; if UpdatePage.SelectedValueIndex = 2 then Mode := 'off';
      Settings := Settings + ',"backend":' + J(Trim(ConnectionPage.Values[0])) +
        ',"port":' + IntToStr(StrToIntDef(ConnectionPage.Values[1], 0)) + ',"mcp_config":' + J(ConnectionPage.Values[3]) +
        ',"workers":' + IntToStr(StrToIntDef(AdvancedPage.Values[0], 0)) + ',"active_chats":' + IntToStr(StrToIntDef(AdvancedPage.Values[1], 0)) +
        ',"shutdown_seconds":' + IntToStr(StrToIntDef(AdvancedPage.Values[2], 0)) + ',"update_mode":' + J(Mode);
      BindAddress := Trim(ConnectionPage.Values[2]);
    end else if ModePage.SelectedValueIndex = 1 then BindAddress := '127.0.0.1' else BindAddress := 'auto';
    Settings := Settings + ',"bind_host":' + J(BindAddress) + '}';
    TempFile := ExpandConstant('{tmp}\gateway-settings.json');
    SetArrayLength(Lines, 1); Lines[0] := Settings;
    if not SaveStringsToUTF8File(TempFile, Lines, False) then RaiseException('Could not save setup settings.');
    Result := TempFile;
end;

function ConfigurationError: String;
var Lines: TArrayOfString;
begin
  Result := 'Gateway configuration is invalid. Check the backend origin and network listen address.';
  if LoadStringsFromFile(ExpandConstant('{tmp}\gateway-config-error.txt'), Lines) and (GetArrayLength(Lines) > 0) then Result := Lines[0];
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var Code: Integer; TempFile, Validator: String;
begin
  Result := '';
  InstallFirewall := WizardIsTaskSelected('firewall') and UsesPhoneNetwork;
  TempFile := WriteWizardSettings;
  { Recommended fresh installs use validated built-in defaults. Preflight custom
    input and retained settings before replacing any installed files. }
  if (ModePage.SelectedValueIndex = 2) or not NeedsInitialSettings or FileExists(ExpandConstant('{app}\current.json')) then begin
    if not RuntimePrepared then begin
      ExtractTemporaryFiles('{app}\releases\{#GatewayVersion}\*');
      RuntimePrepared := True;
    end;
    Validator := ExpandConstant('{tmp}\') + '{app}\releases\{#GatewayVersion}\Gateway.exe';
    if not Exec(Validator, 'validate-settings --version {#GatewayVersion} --root "' + ExpandConstant('{app}') + '" --settings-file "' + TempFile + '" --quiet --error-file "' + ExpandConstant('{tmp}\gateway-config-error.txt') + '"', '', SW_HIDE, ewWaitUntilTerminated, Code) then begin
      Result := 'Windows could not open the bundled settings validator. See the setup log.';
      Exit;
    end;
    RepairDamaged := False;
    if (Code = 12) and not WizardSilent then
      RepairDamaged := MsgBox('The saved settings could not be read. Keep a backup and restore local defaults? Your jobs and conversations will be retained.', mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES;
    if (Code <> 0) and not RepairDamaged then begin
      Result := ConfigurationError;
      Exit;
    end;
    if DirExists(ExpandConstant('{app}\data')) or FileExists(ExpandConstant('{app}\current.json')) then begin
      if not Exec(Validator, 'stop --root "' + ExpandConstant('{app}') + '" --quiet', '', SW_HIDE, ewWaitUntilTerminated, Code) or (Code <> 0) then
        Result := 'Gateway could not stop safely. Finish any active work and retry.';
    end;
  end;
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if CurPageID = wpReady then begin
    ExistingInstallation := FileExists(ExpandConstant('{app}\current.json')) or
                            FileExists(ExpandConstant('{app}\settings.json'));
    if ExistingInstallation then begin
      WizardForm.PageNameLabel.Caption := 'Repair or update Gateway';
      WizardForm.PageDescriptionLabel.Caption := 'Setup checks the existing installation and keeps its settings and saved work.';
    end;
  end;
  if CurPageID = wpFinished then begin
    if RepairDamaged then
      WizardForm.FinishedHeadingLabel.Caption := 'Gateway settings recovered'
    else if ExistingInstallation then
      WizardForm.FinishedHeadingLabel.Caption := 'Gateway installation repaired or updated';
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var TempFile, RepairArg: String; Code: Integer;
begin
  if CurStep = ssPostInstall then begin
    TempFile := WriteWizardSettings;
    RepairArg := ''; if RepairDamaged then RepairArg := ' --repair-damaged';
    if not Exec(ExpandConstant('{app}\releases\{#GatewayVersion}\Gateway.exe'), 'configure' + RepairArg + ' --root "' + ExpandConstant('{app}') + '" --version {#GatewayVersion} --settings-file "' + TempFile + '" --quiet --error-file "' + ExpandConstant('{tmp}\gateway-config-error.txt') + '"', '', SW_HIDE, ewWaitUntilTerminated, Code) or (Code <> 0) then
      RaiseException(ConfigurationError);
    if InstallFirewall then begin
      if not Exec(ExpandConstant('{app}\releases\{#GatewayVersion}\Gateway.exe'), 'firewall --setup-network --root "' + ExpandConstant('{app}') + '" --quiet', '', SW_HIDE, ewWaitUntilTerminated, Code) or (Code <> 0) then
        MsgBox('The firewall rule was not confirmed. Configure a network listener in Gateway Settings and use Allow LAN through firewall when ready.', mbInformation, MB_OK);
    end;
  end;
end;

function InitializeUninstall: Boolean;
var Code: Integer;
begin
  Result := StopInstalled(ExpandConstant('{app}'));
  if not Result then MsgBox('Gateway has active work or could not stop. Finish the work and retry uninstall.', mbError, MB_OK);
  if Result and (FileExists(ExpandConstant('{app}\firewall.json')) or FileExists(ExpandConstant('{app}\phone-firewall.json'))) then begin
    Result := Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'), '-NoProfile -ExecutionPolicy Bypass -File "' + ExpandConstant('{app}\Start-Gateway.ps1') + '" -Command remove-firewall', '', SW_HIDE, ewWaitUntilTerminated, Code) and (Code = 0);
    if not Result then MsgBox('Could not remove this installation''s firewall rule. Allow the administrator prompt or remove the rule in Gateway Settings, then retry uninstall.', mbError, MB_OK);
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then begin
    RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'GPTMobileGateway');
    DeleteFile(ExpandConstant('{app}\current.json'));
  end;
end;
