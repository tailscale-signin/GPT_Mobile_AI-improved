                if (connection?.secretRef != null) {
                    LabeledCheckbox(
                        checked = clearCredential,
                        label = stringResource(R.string.clear_saved_credential),
                        contentDescription = stringResource(R.string.clear_saved_credential),
                        onCheckedChange = onClearCredentialChange
                    )
                }
