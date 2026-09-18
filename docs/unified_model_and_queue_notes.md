                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    Icons.Default.FormatQuote,
                                    contentDescription = stringResource(R.string.quote_message)
                                )
                            },
                            text = { Text(text = stringResource(R.string.quote_message)) },
                            onClick = {
                                onQuoteMessage(message.content, "User")
                                isDropDownMenuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            enabled = canEdit,
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = stringResource(R.string.edit)
                                )
                            },
                            text = { Text(text = stringResource(R.string.edit)) },
                            onClick = {
                                onEditItemClick.invoke()
                                onDismissRequest.invoke()
                            }
                        )
