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
WizardStyle=modern
DisableProgramGroupPage=yes
OutputDir=..\..\dist\windows
OutputBaseFilename=Gateway-{#GatewayVersion}-windows-x64-setup
Compression=lzma2
SolidCompression=yes
CloseApplications=no
RestartApplications=no
UninstallDisplayIcon={app}\releases\{#GatewayVersion}\Gateway.exe
SetupLogging=yes

[Files]
Source: "..\..\dist\windows\Gateway\*"; DestDir: "{app}\releases\{#GatewayVersion}"; Flags: recursesubdirs createallsubdirs ignoreversion
Source: "Start-Gateway.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "README.md"; DestDir: "{app}"; Flags: ignoreversion

[Tasks]
Name: "startup"; Description: "Start Gateway when I sign in"; Flags: checkedonce
Name: "engineering"; Description: "Allow network devices to run engineering tasks"; Flags: unchecked

Name: "firewall"; Description: "Allow Gateway from the local subnet on Private networks (Windows administrator approval)"; Flags: unchecked

[Icons]
Name: "{group}\Start Gateway"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File ""{app}\Start-Gateway.ps1"""; WorkingDir: "{app}"
Name: "{group}\Gateway Settings"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File ""{app}\Start-Gateway.ps1"" -Command settings"; WorkingDir: "{app}"
Name: "{group}\Gateway Logs"; Filename: "{app}\logs"
Name: "{group}\Uninstall Gateway"; Filename: "{uninstallexe}"

[Run]
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File ""{app}\Start-Gateway.ps1"""; Description: "Start Gateway"; Flags: nowait postinstall skipifsilent
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File ""{app}\Start-Gateway.ps1"" -Command settings"; Description: "Open Gateway settings and status"; Flags: nowait postinstall skipifsilent unchecked

[UninstallDelete]
Type: files; Name: "{app}\current.json"
Type: filesandordirs; Name: "{app}\releases"

[Code]
var
  ConnectionPage, PerformancePage, NetworkPage: TInputQueryWizardPage;
  UpdatePage: TInputOptionWizardPage;
  RuntimePrepared: Boolean;

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

procedure InitializeWizard;
begin
  ConnectionPage := CreateInputQueryPage(wpSelectDir, 'Connection settings', 'Choose your model backend and gateway port.', 'The model server is separate. Existing installations retain their settings. Change them later using Gateway Settings.');
  ConnectionPage.Add('Model backend origin (without /v1):', False);
  ConnectionPage.Add('Gateway port (1024-65535):', False);
  ConnectionPage.Add('Existing MCP JSON file (optional, absolute path):', False);
  ConnectionPage.Values[0] := 'http://127.0.0.1:8080';
  ConnectionPage.Values[1] := '8090';
  PerformancePage := CreateInputQueryPage(ConnectionPage.ID, 'Performance and recovery', 'Set bounded concurrency and shutdown grace.', 'Conservative defaults help keep interactive work responsive.');
  PerformancePage.Add('Concurrent workers (1-16):', False);
  PerformancePage.Add('Maximum admitted chats (workers-64):', False);
  PerformancePage.Add('Shutdown grace in seconds (10-120):', False);
  PerformancePage.Values[0] := '2'; PerformancePage.Values[1] := '8'; PerformancePage.Values[2] := '30';
  NetworkPage := CreateInputQueryPage(PerformancePage.ID, 'Network listening', 'Choose which PC interfaces can accept connections.', '127.0.0.1 keeps access on this PC. Enter a specific PC interface IP or 0.0.0.0 for all IPv4 interfaces. No API token is required. Every device that can reach the listener can use its enabled models and tools. Use a trusted LAN/VPN, or a separately authenticated HTTPS proxy for internet access.');
  NetworkPage.Add('Listen address (127.0.0.1, PC interface IP, or 0.0.0.0):', False);
  NetworkPage.Values[0] := '127.0.0.1';
  UpdatePage := CreateInputOptionPage(NetworkPage.ID, 'Updates', 'Choose how Gateway updates.', 'Checks run every 24 hours. Auto updates wait until all active and paused work is finished. A failed runtime check rolls back. Update settings remain available after install.', True, False);
  UpdatePage.Add('Notify me when a stable update is available (recommended)');
  UpdatePage.Add('Automatically install verified stable updates when idle');
  UpdatePage.Add('Do not check automatically');
  UpdatePage.SelectedValueIndex := 0;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var P, W, A, G: Integer;
begin
  Result := True;
  if CurPageID = ConnectionPage.ID then begin
    P := StrToIntDef(ConnectionPage.Values[1], 0);
    Result := (P >= 1024) and (P <= 65535) and (ConnectionPage.Values[0] <> '');
    if (ConnectionPage.Values[2] <> '') and not FileExists(ConnectionPage.Values[2]) then Result := False;
    if not Result then MsgBox('Enter a valid port, backend origin and existing MCP file.', mbError, MB_OK);
  end;
  if CurPageID = PerformancePage.ID then begin
    W := StrToIntDef(PerformancePage.Values[0], 0); A := StrToIntDef(PerformancePage.Values[1], 0); G := StrToIntDef(PerformancePage.Values[2], 0);
    Result := (W >= 1) and (W <= 16) and (A >= W) and (A <= 64) and (G >= 10) and (G <= 120);
    if not Result then MsgBox('Check the performance ranges shown above.', mbError, MB_OK);
  end;
  if CurPageID = NetworkPage.ID then begin
    Result := NetworkPage.Values[0] <> '';
    if not Result then MsgBox('Enter a listen IP address.', mbError, MB_OK);
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
var Settings, Mode, TempFile: String; Lines: TArrayOfString;
begin
    Mode := 'notify'; if UpdatePage.SelectedValueIndex = 1 then Mode := 'auto'; if UpdatePage.SelectedValueIndex = 2 then Mode := 'off';
    Settings := '{"backend":' + J(ConnectionPage.Values[0]) + ',"port":' + IntToStr(StrToIntDef(ConnectionPage.Values[1], 8090)) + ',"mcp_config":' + J(ConnectionPage.Values[2]) + ',"workers":' + PerformancePage.Values[0] + ',"active_chats":' + PerformancePage.Values[1] + ',"shutdown_seconds":' + PerformancePage.Values[2] + ',"bind_host":' + J(NetworkPage.Values[0]) + ',"update_mode":' + J(Mode) + ',"start_at_login":' + B(WizardIsTaskSelected('startup')) + ',"engineering":' + B(WizardIsTaskSelected('engineering')) + '}';
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
  TempFile := WriteWizardSettings;
  if not RuntimePrepared then begin
    ExtractTemporaryFiles('{app}\releases\{#GatewayVersion}\*');
    RuntimePrepared := True;
  end;
  Validator := ExpandConstant('{tmp}\') + '{app}\releases\{#GatewayVersion}\Gateway.exe';
  if not Exec(Validator, 'validate-settings --root "' + ExpandConstant('{app}') + '" --settings-file "' + TempFile + '" --quiet --error-file "' + ExpandConstant('{tmp}\gateway-config-error.txt') + '"', '', SW_HIDE, ewWaitUntilTerminated, Code) or (Code <> 0) then begin
    Result := ConfigurationError;
    Exit;
  end;
  if not StopInstalled(ExpandConstant('{app}')) then Result := 'Gateway has active work or could not stop. Finish or cancel that work in the app, then retry installation. No process has been forcibly closed.';
end;

procedure CurStepChanged(CurStep: TSetupStep);
var TempFile: String; Code: Integer;
begin
  if CurStep = ssPostInstall then begin
    TempFile := WriteWizardSettings;
    if not Exec(ExpandConstant('{app}\releases\{#GatewayVersion}\Gateway.exe'), 'configure --root "' + ExpandConstant('{app}') + '" --version {#GatewayVersion} --settings-file "' + TempFile + '" --quiet --error-file "' + ExpandConstant('{tmp}\gateway-config-error.txt') + '"', '', SW_HIDE, ewWaitUntilTerminated, Code) or (Code <> 0) then
      RaiseException(ConfigurationError);
    if WizardIsTaskSelected('firewall') then begin
      if not Exec(ExpandConstant('{app}\releases\{#GatewayVersion}\Gateway.exe'), 'firewall --root "' + ExpandConstant('{app}') + '" --quiet', '', SW_HIDE, ewWaitUntilTerminated, Code) or (Code <> 0) then
        MsgBox('The firewall rule was not confirmed. Configure a network listener in Gateway Settings and use Allow LAN through firewall when ready.', mbInformation, MB_OK);
    end;
  end;
end;

function InitializeUninstall: Boolean;
var Code: Integer;
begin
  Result := StopInstalled(ExpandConstant('{app}'));
  if not Result then MsgBox('Gateway has active work or could not stop. Finish the work and retry uninstall.', mbError, MB_OK);
  if Result and FileExists(ExpandConstant('{app}\firewall.json')) then begin
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
