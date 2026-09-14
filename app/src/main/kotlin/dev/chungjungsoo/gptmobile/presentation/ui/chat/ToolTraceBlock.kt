    // Make the tool call chat bubble more opaque for better visibility
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.15f))
            .semantics { contentDescription = traceBlockDescription },
    ) {
