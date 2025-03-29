import javax.microedition.lcdui.*;
import javax.microedition.midlet.*;
import javax.microedition.io.*;
import java.io.*;
import java.util.Vector;

public class SlackClientMidlet
    extends MIDlet 
    implements CommandListener {
  private Form mMainForm;
  private ChoiceGroup mChoiceGroup;
  private TextField mTextField;
  private String mApiUrl = "http://localhost:3000/messages/"; // Replace with your actual API URL
  private String mSocketUrl = "socket://localhost:3001"; // Replace with your socket server details
  private SocketConnection mSocketConn;
  private DataInputStream mSocketIn;
  private Thread mSocketThread;
  private boolean mSocketRunning;
  private Command mSendCommand;
  private Command mBackCommand;
  
  // Thread-related variables
  private boolean mInThreadView = false;
  private String mCurrentThreadTs = null;
  private String mThreadParentMessage = null;
  private String mThreadParentAuthor = null;
  private String mThreadParentTime = null;
  
  // Channel configuration - pairs of display names and channel IDs
  private String[][] mChannels = {
    {"#what-is-my-slack-id", "C0159TSJVH8"},
    {"#retrospect", "C07MUFXNG82"}
  };
  
  public SlackClientMidlet() {
    mMainForm = new Form("Slack Client");
    
    // Create a dropdown (ChoiceGroup with POPUP)
    mChoiceGroup = new ChoiceGroup("Channel:", Choice.POPUP);
    
    // Add channels from configuration
    for (int i = 0; i < mChannels.length; i++) {
      mChoiceGroup.append(mChannels[i][0], null);
    }
    
    // Add ItemStateListener to the ChoiceGroup to detect channel changes
    mMainForm.setItemStateListener(new ItemStateListener() {
      public void itemStateChanged(Item item) {
        if (item == mChoiceGroup) {
          // When channel is changed, refresh messages and reconnect socket
          if (mInThreadView) {
            // If in thread view, go back to channel view first
            returnToChannelView();
          } else {
            loadMessages();
            connectSocket();
          }
        }
      }
    });
    
    // Create a text input field
    mTextField = new TextField("Message:", "", 100, TextField.ANY);
    
    // Add components to the form
    mMainForm.append(mChoiceGroup);
    mMainForm.append(mTextField);
    
    // Add commands
    mSendCommand = new Command("Send", Command.OK, 1);
    mBackCommand = new Command("Back", Command.BACK, 1);
    mMainForm.addCommand(mSendCommand);
    mMainForm.addCommand(new Command("Exit", Command.EXIT, 0));
    mMainForm.setCommandListener(this);
  }
  
  public void startApp() {
    Display.getDisplay(this).setCurrent(mMainForm);
    loadMessages();
    connectSocket();
  }
  
  public void pauseApp() {
    disconnectSocket();
  }
  
  public void destroyApp(boolean unconditional) {
    disconnectSocket();
  }
  
  public void commandAction(Command c, Displayable s) {
    if (c.getCommandType() == Command.EXIT) {
      notifyDestroyed();
    } else if (c == mSendCommand) {
      sendMessage();
    } else if (c == mBackCommand) {
      returnToChannelView();
    } else if (c.getLabel().equals("View Thread")) {
      // We need to determine which message was selected
      // This is handled by the ItemCommandListener on each message item
      // So this code path shouldn't be reached in normal operation
      // For safety, default to returning to channel view
      returnToChannelView();
    }
  }
  
  /**
   * Loads messages from the API and displays them in the form
   */
  private void loadMessages() {
    Thread t = new Thread() {
      public void run() {
        HttpConnection conn = null;
        InputStream is = null;
        
        try {
          // Clear everything after the TextField
          while (mMainForm.size() > 2) {
            mMainForm.delete(2);
          }
          
          // Add loading indicator
          mMainForm.append(new StringItem(null, "Loading messages..."));
          
          // Get the current selected channel ID
          String channelId = getSelectedChannelId();
          
          // Connect to the API and fetch messages for the specific channel
          String channelUrl = mApiUrl + channelId;
          conn = (HttpConnection) Connector.open(channelUrl);
          conn.setRequestMethod("GET");
          
          if (conn.getResponseCode() == HttpConnection.HTTP_OK) {
            is = conn.openInputStream();
            
            // Read and process the CSV data
            InputStreamReader isr = new InputStreamReader(is);
            StringBuffer sb = new StringBuffer();
            int ch;
            while ((ch = isr.read()) != -1) {
              sb.append((char) ch);
            }
            
            // Process the CSV data
            String csvData = sb.toString();
            Vector messages = parseCSV(csvData);
            
            // Remove loading indicator
            mMainForm.delete(2);
            
            // Display the messages
            for (int i = 0; i < messages.size(); i++) {
              String[] message = (String[]) messages.elementAt(i);
              String threadTs = message[0];
              String author = message[1];
              String time = message[2];
              String text = message[3];
              
              StringItem messageItem = new StringItem(author + " (" + time + ")", text);
              
              // Make the message interactive
              final String fThreadTs = threadTs;
              final String fText = text;
              final String fAuthor = author;
              final String fTime = time;
              // Add a "View Thread" command
              Command threadCommand = new Command("View Thread", Command.ITEM, 1);
              // We can't store data in the Command object, we'll use a closure via ItemCommandListener instead
              messageItem.addCommand(threadCommand);
              // Remove invalid call to setScreen - we'll use the ItemCommandListener to handle this
              messageItem.addCommand(threadCommand);
              messageItem.setDefaultCommand(threadCommand);
              messageItem.setItemCommandListener(new ItemCommandListener() {
                public void commandAction(Command c, Item item) {
                  if (c.getLabel().equals("View Thread")) {
                    mThreadParentMessage = fText;
                    mThreadParentAuthor = fAuthor;
                    mThreadParentTime = fTime;
                    openThreadView(fThreadTs);
                  }
                }
              });
              

              mMainForm.append(messageItem);
            }
          } else {
            // Error handling
            mMainForm.delete(2);
            mMainForm.append(new StringItem(null, "Error: " + conn.getResponseCode()));
          }
        } catch (Exception e) {
          mMainForm.delete(2);
          mMainForm.append(new StringItem(null, "Error: " + e.toString()));
        } finally {
          try {
            if (is != null) is.close();
            if (conn != null) conn.close();
          } catch (IOException e) {
            // Ignore
          }
        }
      }
    };
    t.start();
  }
  
  /**
   * Opens the thread view for a specific message
   */
  private void openThreadView(final String threadTs) {
    mInThreadView = true;
    mCurrentThreadTs = threadTs;
    
    // Update UI state for thread view
    // Don't remove the send command, we need it for replies
    mMainForm.addCommand(mBackCommand);
    mTextField.setLabel("Reply to thread:");
    
    // Fetch thread messages
    loadThreadMessages(threadTs);
  }
  
  /**
   * Return to the main channel view
   */
  private void returnToChannelView() {
    mInThreadView = false;
    mCurrentThreadTs = null;
    
    // Update UI state for channel view
    mMainForm.removeCommand(mBackCommand);
    // No need to add send command since it should already be there
    mTextField.setLabel("Message:");
    
    // Return to channel view
    loadMessages();
    connectSocket();
  }
  
  /**
   * Loads thread messages for a specific thread
   */
  private void loadThreadMessages(final String threadTs) {
    Thread t = new Thread() {
      public void run() {
        HttpConnection conn = null;
        InputStream is = null;
        
        try {
          // Clear everything after the TextField
          while (mMainForm.size() > 2) {
            mMainForm.delete(2);
          }
          
          // Add loading indicator
          mMainForm.append(new StringItem(null, "Loading thread..."));
          
          // Get the current selected channel ID
          String channelId = getSelectedChannelId();
          
          // Add parent message at the top of the thread view
          final StringItem parentItem = new StringItem(
              mThreadParentAuthor + " (" + mThreadParentTime + ")",
              mThreadParentMessage);
          
          // Make parent message clickable to go back
          Command backCommand = new Command("Back to Channel", Command.ITEM, 1);
          parentItem.addCommand(backCommand);
          parentItem.setDefaultCommand(backCommand);
          parentItem.setItemCommandListener(new ItemCommandListener() {
            public void commandAction(Command c, Item item) {
              returnToChannelView();
            }
          });
          
          // Connect to the API and fetch thread messages
          String threadUrl = mApiUrl + channelId + "/thread/" + threadTs;
          conn = (HttpConnection) Connector.open(threadUrl);
          conn.setRequestMethod("GET");
          
          if (conn.getResponseCode() == HttpConnection.HTTP_OK) {
            is = conn.openInputStream();
            
            // Read and process the CSV data
            InputStreamReader isr = new InputStreamReader(is);
            StringBuffer sb = new StringBuffer();
            int ch;
            while ((ch = isr.read()) != -1) {
              sb.append((char) ch);
            }
            
            // Process the CSV data
            String csvData = sb.toString();
            Vector messages = parseCSV(csvData);
            
            // Remove loading indicator
            mMainForm.delete(2);
            
            // Add parent message first
            mMainForm.append(parentItem);
            
            // Display thread separator
            mMainForm.append(new StringItem(null, "--- Thread Replies ---"));
            
            // Display the thread messages
            if (messages.size() == 0) {
              mMainForm.append(new StringItem(null, "No replies yet."));
            } else {
              for (int i = 0; i < messages.size(); i++) {
                String[] message = (String[]) messages.elementAt(i);
                String msgThreadTs = message[0];
                String author = message[1];
                String time = message[2];
                String text = message[3];
                
                // Use indentation to show thread replies
                StringItem threadItem = new StringItem("    "  + author + " (" + time + ")", text);
                mMainForm.append(threadItem);
              }
            }
          } else {
            // Error handling
            mMainForm.delete(2);
            mMainForm.append(parentItem);
            mMainForm.append(new StringItem(null, "Error loading thread: " + conn.getResponseCode()));
          }
        } catch (Exception e) {
          mMainForm.delete(2);
          // Still show parent message even if there's an error
          final StringItem parentItem = new StringItem(
              mThreadParentAuthor + " (" + mThreadParentTime + ")",
              mThreadParentMessage);
          mMainForm.append(parentItem);
          mMainForm.append(new StringItem(null, "Error: " + e.toString()));
        } finally {
          try {
            if (is != null) is.close();
            if (conn != null) conn.close();
          } catch (IOException e) {
            // Ignore
          }
        }
      }
    };
    t.start();
  }
  
  /**
   * Connects to socket server for receiving real-time messages
   */
  private void connectSocket() {
    disconnectSocket(); // Ensure any existing connection is closed
    
    mSocketRunning = true;
    mSocketThread = new Thread() {
      public void run() {
        try {
          // Connect to the socket server
          mSocketConn = (SocketConnection) Connector.open(mSocketUrl);
          mSocketIn = mSocketConn.openDataInputStream();
          DataOutputStream out = mSocketConn.openDataOutputStream();
          
          // Get the currently selected channel ID
          final String channelId = getSelectedChannelId();
          
          // Send subscription request for the specific channel
          out.writeUTF("SUBSCRIBE " + channelId);
          out.flush();
          
          // Listen for incoming messages 
          StringBuffer buffer = new StringBuffer();
          int ch;
          while (mSocketRunning && (ch = mSocketIn.read()) != -1) {
            if (ch == '\n') {
              // Process complete line
              final String line = buffer.toString().trim();
              buffer = new StringBuffer();
              
              if (line.length() > 0) {
                // Process the message on the UI thread
                Display.getDisplay(SlackClientMidlet.this).callSerially(new Runnable() {
                  public void run() {
                    addNewMessage(line);
                  }
                });
              }
            } else {
              buffer.append((char)ch);
            }
          }
        } catch (final IOException e) {
          if (mSocketRunning) {
            // Only show error if we didn't intentionally disconnect
            Display.getDisplay(SlackClientMidlet.this).callSerially(new Runnable() {
              public void run() {
                // addDebugMessage("Socket error: " + e.toString());
              }
            });
          }
        } finally {
          closeSocketResources();
        }
      }
    };
    mSocketThread.start();
  }
  
  /**
   * Disconnects from the socket server
   */
  private void disconnectSocket() {
    mSocketRunning = false;
    closeSocketResources();
    if (mSocketThread != null) {
      mSocketThread.interrupt();
      mSocketThread = null;
    }
  }
  
  /**
   * Closes socket resources
   */
  private void closeSocketResources() {
    try {
      if (mSocketIn != null) {
        mSocketIn.close();
        mSocketIn = null;
      }
      if (mSocketConn != null) {
        mSocketConn.close();
        mSocketConn = null;
      }
    } catch (IOException e) {
      // Ignore
    }
  }
  
  /**
   * Adds a new message to the form
   * Expected format: threadTs|author|time|message
   */
  private void addNewMessage(String messageData) {
    try {
      // First try the standard format
      String[] parts = split(messageData, '|');
      if (parts.length >= 4) {
        String threadTs = parts[0];
        String author = parts[1];
        String time = parts[2];
        String text = parts[3];
        
        // Check if this is a thread message
        if (!threadTs.equals("0") && mInThreadView && 
            threadTs.equals(mCurrentThreadTs)) {
          // This is a message for the current thread - add it with indentation
          mMainForm.insert(2, new StringItem("    " + author + " (" + time + ")", text));
        } else if (!mInThreadView) {
          // Only add to main view if not in thread view
          StringItem messageItem = new StringItem(author + " (" + time + ")", text);
          
          // Make the message interactive
          final String fThreadTs = threadTs;
          final String fText = text;
          final String fAuthor = author;
          final String fTime = time;
          Command threadCommand = new Command("View Thread", Command.ITEM, 1);
          // Using the ItemCommandListener to capture thread timestamp instead
          messageItem.addCommand(threadCommand);
          // Remove invalid call to setScreen - we'll use the ItemCommandListener instead
          messageItem.addCommand(threadCommand);
          messageItem.setDefaultCommand(threadCommand);
          messageItem.setItemCommandListener(new ItemCommandListener() {
            public void commandAction(Command c, Item item) {
              if (c.getLabel().equals("View Thread")) {
                mThreadParentMessage = fText;
                mThreadParentAuthor = fAuthor;
                mThreadParentTime = fTime;
                openThreadView(fThreadTs);
              }
            }
          });
          
          
          
          mMainForm.insert(2, messageItem);
        }
        return;
      } 
          
      // If all parsing fails, just display the raw message
      mMainForm.insert(2, new StringItem("New message", messageData));
      
    } catch (Exception e) {
      // If there's any error in processing, log it and display the raw message
      addDebugMessage("Error processing message: " + e.toString());
      mMainForm.insert(2, new StringItem("Raw message", messageData));
    }
  }

  
  /**
   * Add a debug message to the UI
   */
  private void addDebugMessage(String message) {
    final String debugMsg = "[DEBUG] " + message;
    Display.getDisplay(this).callSerially(new Runnable() {
      public void run() {
        // Add at a fixed position to keep debug messages together
        mMainForm.append(new StringItem(null, debugMsg));
      }
    });
  }
  
  /**
   * Parses CSV data in the format threadTs|author|time|message
   */
  private Vector parseCSV(String csvData) {
    Vector result = new Vector();
    
    // Split the CSV data by lines
    String[] lines = split(csvData, '\n');
    
    for (int i = 0; lines != null && i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.length() > 0) {
        // Split each line by the delimiter
        String[] parts = split(line, '|');
        if (parts.length >= 4) {  // Now expecting at least 4 parts
          result.addElement(parts);
        }
      }
    }
    
    return result;
  }
  
  /**
   * Utility method to split a string by delimiter
   */
  private String[] split(String str, char delimiter) {
    Vector parts = new Vector();
    StringBuffer sb = new StringBuffer();
    
    for (int i = 0; str != null && i < str.length(); i++) {
      char c = str.charAt(i);
      if (c == delimiter) {
        parts.addElement(sb.toString());
        sb = new StringBuffer();
      } else {
        sb.append(c);
      }
    }
    
    if (sb.length() > 0) {
      parts.addElement(sb.toString());
    }
    
    String[] result = new String[parts.size()];
    for (int i = 0; i < parts.size(); i++) {
      result[i] = (String) parts.elementAt(i);
    }
    
    return result;
  }
  
  /**
   * Sends the message entered by the user
   */
  private void sendMessage() {
    final String message = mTextField.getString().trim();
    if (message.length() == 0) {
      return; // Don't send empty messages
    }
    
    final String channelName = mChoiceGroup.getString(mChoiceGroup.getSelectedIndex());
    final String channelId = getSelectedChannelId();
    
    Thread t = new Thread() {
      public void run() {
        HttpConnection conn = null;
        InputStream is = null;
        
        try {
          // Create a URL with query parameters
          String url = mApiUrl + "send/" + channelId + 
                       "?message=" + urlEncode(message);
          
          // If we're in a thread, add the thread_ts parameter
          if (mInThreadView && mCurrentThreadTs != null) {
            url += "&thread_ts=" + urlEncode(mCurrentThreadTs);
          }
          
          conn = (HttpConnection) Connector.open(url);
          conn.setRequestMethod("GET");
          
          int responseCode = conn.getResponseCode();
          if (responseCode == HttpConnection.HTTP_OK) {
            // Message sent successfully
            Display.getDisplay(SlackClientMidlet.this).callSerially(new Runnable() {
              public void run() {
                // Clear the text field
                mTextField.setString("");
                
                // Optionally display a confirmation (temporarily)
                final StringItem confirmItem = new StringItem(null, "Message sent!");
                mMainForm.insert(2, confirmItem);
                
                // Remove the confirmation after a delay
                new Thread() {
                  public void run() {
                    try {
                      Thread.sleep(2000);
                      Display.getDisplay(SlackClientMidlet.this).callSerially(new Runnable() {
                        public void run() {
                          // Find the confirmation item position in the form
                          for (int i = 0; mMainForm != null && i < mMainForm.size(); i++) {
                            if (mMainForm.get(i) == confirmItem) {
                              mMainForm.delete(i);
                              break;
                            }
                          }
                        }
                      });
                    } catch (InterruptedException ie) {
                      // Ignore
                    }
                  }
                }.start();
                
                // Refresh the current view (channel or thread)
                if (mInThreadView && mCurrentThreadTs != null) {
                  loadThreadMessages(mCurrentThreadTs);
                } else {
                  loadMessages();
                }
              }
            });
          } else {
            // Show error if message failed to send
            final String error = "Error sending message: " + responseCode;
            Display.getDisplay(SlackClientMidlet.this).callSerially(new Runnable() {
              public void run() {
                mMainForm.insert(2, new StringItem(null, error));
              }
            });
          }
        } catch (final IOException e) {
          Display.getDisplay(SlackClientMidlet.this).callSerially(new Runnable() {
            public void run() {
              mMainForm.insert(2, new StringItem(null, "Error: " + e.toString()));
            }
          });
        } finally {
          try {
            if (is != null) is.close();
            if (conn != null) conn.close();
          } catch (IOException e) {
            // Ignore
          }
        }
      }
    };
    t.start();
  }
  
  /**
   * Encode a string for use as a URL parameter
   */
  private String urlEncode(String s) {
    if (s == null) {
      return "";
    }
    
    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || 
          (c >= '0' && c <= '9') || c == '-' || c == '_' || 
          c == '.' || c == '*') {
        sb.append(c);
      } else if (c == ' ') {
        sb.append('+');
      } else {
        sb.append('%');
        // Convert to hex without using Character.forDigit
        String hex = Integer.toHexString((int) c);
        if (hex.length() == 1) {
          sb.append('0');
        }
        sb.append(hex.toUpperCase());
      }
    }
    return sb.toString();
  }
  
  /**
   * Gets the channel ID for the currently selected channel
   */
  private String getSelectedChannelId() {
    int selectedIndex = mChoiceGroup.getSelectedIndex();
    if (selectedIndex >= 0 && selectedIndex < mChannels.length) {
      return mChannels[selectedIndex][1];
    }
    return "";
  }
}