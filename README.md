# SlackClient for J2ME

A lightweight Slack client implementation for Java ME (J2ME) devices, because @nora asked nicely.

![alt text](image.png)

> [This was built for Retrospect](https://retrospect.hackclub.com/j2me)

## Features

- Can be configured for any number of channels, with Channel Hot Swap
- Real-time Direct Messages via socket connection
- View and join in on the fun in message threads
- Send new messages and reply to threads

## Requirements

- J2ME compatible device or emulator
- Java ME MIDP 2.0 and CLDC 1.1
- Server-side API for Slack integration
- Socket server for real-time updates

## Setup

### Server Configuration

This client requires two server-side components, both of which are implemented [here](https://github.com/Subhi-Dev/MIDlet-API)

1. **HTTP API Server**

   - Default endpoint: `http://localhost:3000/messages/`
   - Provides channel messages in CSV format: `threadTs|author|time|message`
   - Accepts message send requests at `/messages/send/{channelId}`
   - Supports thread viewing at `/messages/{channelId}/thread/{threadTs}`

2. **Socket Server**
   - Default endpoint: `socket://localhost:3001`
   - Handles real-time message delivery
   - Accepts subscription requests in format: `SUBSCRIBE {channelId}`
   - Delivers messages in format: `threadTs|author|time|message`

### Application Configuration

Configure the application by modifying these variables in the source code:

```java
private String mApiUrl = "http://localhost:3000/messages/";
private String mSocketUrl = "socket://localhost:3001";
```

The channel configuration is stored in the `mChannels` array:

```java
private String[][] mChannels = {
  {"#what-is-my-slack-id", "C0159TSJVH8"},
  {"#retrospect", "C07MUFXNG82"}
};
```

## Building and Deployment

1. Compile the MIDlet using the J2ME Wireless Toolkit
2. Create the JAD file with appropriate attributes.
3. Install on a J2ME compatible device or emulator.

## Usage

### Main View

- **Channel Selection**: Use the dropdown at the top to select a channel
- **Message Sending**: Type your message in the text field and press "Send"
- **View Thread**: Click on any message to view its thread
- **Navigation**: Use the "Back" button to return from thread view to main view

### Thread View

- **Parent Message**: The original message appears at the top
- **Thread Replies**: Replies appear indented below the separator
- **Reply to Thread**: Enter text in the field and press "Send"
- **Return to Channel**: Click the parent message or press "Back"

## Message Format

The application expects all messages in the format: `threadTs|author|time|text`

- `threadTs`: Thread identifier
- `author`: Name of the message author
- `time`: Timestamp of the message
- `text`: Message content

## Limitations

- Limited formatting support for messages
- No file upload capability
- No emoji reactions
- No user presence indicators
- Limited channel management

## License

This project is open-source.
