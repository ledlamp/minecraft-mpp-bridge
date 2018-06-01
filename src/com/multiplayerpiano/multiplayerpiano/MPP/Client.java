// original @ https://cdn.discordapp.com/attachments/338703750115885056/344970304155811840/Client.java
package com.multiplayerpiano.multiplayerpiano.MPP;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;


public class Client extends EventEmitter {

    private URI uri;
    private WebSocketClient ws;
    public long serverTimeOffset = 0;
    private JSONObject user;
    private String participantId;
    public JSONObject channel;
    public JSONObject ppl = new JSONObject();
    private boolean shouldConnect = false;
    private long connectionTime;
    private int connectionAttempts;
    private String desiredChannelId = "";
    private JSONObject desiredChannelSettings = null;
    private JSONArray noteBuffer = new JSONArray();
    private long noteBufferTime = 0;
    private Timer noteFlushInterval;
    private Timer pingInterval;

    private JSONObject offlineParticipant; {
        try {
            offlineParticipant = new JSONObject().put("name", "").put("color", "#777");
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
    private JSONObject offlineChannelSettings; {
        try {
            offlineChannelSettings = new JSONObject().put("lobby", true).put("visible", false).put("chat", false).put("crownsolo", false);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }





    public Client(URI uri){
        this.uri = uri;
        emit("status", "Piano (Offline Mode)"); // todo this is weird
        bindEventListeners();
    }

    public boolean isConnected() {
        return ws != null && ws.getReadyState() == WebSocket.READYSTATE.OPEN;
    }

    public boolean isConnecting() {
        return ws != null && ws.getReadyState() == WebSocket.READYSTATE.CONNECTING;
    }

    public void start(){
        shouldConnect = true;
	    connect();
    }

    public void stop(){
        shouldConnect = false;
	    ws.close();
    }

    public void connect(){
        if(!shouldConnect || isConnected() || isConnecting())
		    return;
        final Client client = this;
        emit("status", "Connecting...");
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Origin", "http://www.multiplayerpiano.com");
        this.ws = new WebSocketClient(uri, new Draft_6455(), headers, 0) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                connectionTime = System.currentTimeMillis();
                noteBuffer = new JSONArray();
                noteBufferTime = 0;
                startTimers();
                try {
                    client.send(new JSONObject().put("m", "hi"));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                
                emit("connect");
                emit("status", "Joining channel...");
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                user = null;
                participantId = null;
                channel = null;
                setParticipants(new JSONArray());
                stopTimers();
                emit("disconnect", reason);
                emit("status", "Offline mode");
                // reconnect!
                if(connectionTime > 0) {
                    connectionTime = 0;
                    connectionAttempts = 0;
                } else {
                    ++connectionAttempts;
                }
                int[] ms_lut = new int[]{50, 2950, 7000, 10000};
                int idx = connectionAttempts;
                if(idx > ms_lut.length) idx = ms_lut.length - 1;
                int ms = ms_lut[idx];
                new Timer().schedule(new TimerTask() {
                		@Override public void run() {
                			client.connect();
                		}
                }, ms);
            }

            @Override
            public void onError(Exception ex) {
                ex.printStackTrace();
            }

            @Override
            public void onMessage(String message) {
                try {
                    JSONArray json = new JSONArray(message);
                    for(int i = 0; i < json.length(); i++) {
                        JSONObject msg = json.optJSONObject(i);
                        if(msg == null) continue;
                        client.emit(msg.optString("m"), msg);
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        };
        ws.connect();
    }
    
    private void startTimers() {
	    TimerTask sendPings = new TimerTask() {
	        @Override public void run() {
	            try {
	                send(new JSONObject().put("m", "t").put("e", System.currentTimeMillis()));
	            } catch (JSONException e) {
	                e.printStackTrace();
	            }
	        }
	    };
	    pingInterval = new Timer();
        pingInterval.scheduleAtFixedRate(sendPings, 0, 20000);
	    
	    
	    TimerTask sendNotes = new TimerTask() {
	        @Override public void run() {
	            if(noteBufferTime != 0 && noteBuffer.length() > 0) {
	                try {
	                    send(new JSONObject().put("m", "n").put("t", noteBufferTime + serverTimeOffset).put("n", noteBuffer));
	                } catch (JSONException e) {
	                    e.printStackTrace();
	                }
	                noteBufferTime = 0;
	                noteBuffer = new JSONArray(); // no way to clear it... >:(
	            }
	        }
	    };
	    noteFlushInterval = new Timer();
        noteFlushInterval.scheduleAtFixedRate(sendNotes, 0, 200);
    }
    
    private void stopTimers() {
    		pingInterval.cancel();
    		noteFlushInterval.cancel();
    }

    public void bindEventListeners(){
        final Client client = this;
        this.on("hi", new Callback() {
            @Override
            public void call(Object... args) {
                JSONObject msg = (JSONObject)args[0];
                user = msg.optJSONObject("u"); // null if none
                receiveServerTime(msg.optLong("t")); // 0L if none
                setChannel(desiredChannelId, desiredChannelSettings);
            }
        });
        this.on("t", new Callback() {
            @Override
            public void call(Object... args) {
                JSONObject msg = (JSONObject)args[0];
                receiveServerTime(msg.optLong("t"));
            }
        });
        this.on("ch", new Callback() {
            @Override
            public void call(Object... args) {
                JSONObject msg = (JSONObject)args[0];
                channel = msg.optJSONObject("ch");
                desiredChannelId = channel.optString("_id");
                if(msg.has("p")) participantId = msg.optString("p");
                setParticipants(msg.optJSONArray("ppl"));
            }
        });
        this.on("p", new Callback() {
            @Override
            public void call(Object... args) {
                JSONObject msg = (JSONObject)args[0];
                participantUpdate(msg);
                emit("participant update", findParticipantById(msg.optString("id")));
            }
        });
        this.on("m", new Callback() {
            @Override
            public void call(Object... args) {
                JSONObject msg = (JSONObject)args[0];
                participantUpdate(msg);
            }
        });
        this.on("bye", new Callback() {
            @Override
            public void call(Object... args) {
                JSONObject msg = (JSONObject)args[0];
                removeParticipant(msg.optString("p"));
            }
        });
    }

    public void send(String raw){
        if(isConnected()) {
            ws.send(raw);
        }
    }

    public void send(JSONObject m){
        this.send("["+m.toString()+"]");
    }

    public void setChannel(String id, JSONObject set){
        if(id != null) desiredChannelId = id;
        if(desiredChannelId == null) desiredChannelId = "lobby";

        if(set != null) desiredChannelSettings = set;
        try {
            send(new JSONObject().put("m", "ch").put("_id", desiredChannelId).putOpt("set", desiredChannelSettings));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public JSONObject getOwnParticipant(){
        return findParticipantById(participantId);
    }

    public void setParticipants(JSONArray ppl){
        // remove participants who left
        JSONArray ids = this.ppl.names();
        if(ids != null) {
            for (int i = ids.length() - 1; i >= 0; i--) {
                boolean found = false;
                for (int j = ppl.length() - 1; j >= 0; j--) {
                    if (ppl.optJSONObject(j).optString("id").equals(ids.opt(i))) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    removeParticipant(ids.optString(i));
                }
            }
        }
        // update all
        for(int i = ppl.length(); i >= 0; i--) {
            JSONObject part = ppl.optJSONObject(i);
            if(part != null) participantUpdate(part);
        }
    }

    public int countParticipants(){
        return ppl.length();
    }

    public void participantUpdate(JSONObject update) {
        try {
            String id = update.getString("id");
            JSONObject part = ppl.optJSONObject(id);
            if (part != null) {
                mergeJSON(part, update);
            } else {
                ppl.put(id, mergeJSON(new JSONObject(), update));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void removeParticipant(String id) {
        JSONObject part = ppl.optJSONObject(id);
        if(part != null) {
            ppl.remove(id);
            emit("participant removed", part);
            emit("count", countParticipants());
        }
    }

    public JSONObject findParticipantById(String id){
        JSONObject part = ppl.optJSONObject(id);
        if(part != null)
            return part;
        else
            return offlineParticipant;
    }

    public boolean isOwner() {
        return true; // todo return this.channel && this.channel.crown && this.channel.crown.participantId === this.participantId;
    }

    public boolean preventsPlaying() {
        return isConnected() && !isOwner() && getChannelSetting("crownsolo");
    }

    private boolean getChannelSetting(String name) {
        return offlineChannelSettings.optBoolean(name); // todo get in real channel settings...  with DEFAULTS
    }

    private void receiveServerTime(long time) {
        if(time < 0) return;
        long now = System.currentTimeMillis();
        serverTimeOffset = time - now; // mostly time zone offset
        //if(echo) this.serverTimeOffset += echo - now;	// mostly round trip time offset

    }

    public void startNote(String note, double vel) {
        if(isConnected()) {
            try {
                JSONObject n = new JSONObject().put("n", note);
                if(vel >= 0) {
                    BigDecimal bd = new BigDecimal(vel).setScale(3, RoundingMode.HALF_EVEN);
                    n.put("v", bd.toString());
                }
                if(noteBufferTime == 0) {
                    noteBufferTime = System.currentTimeMillis();
                } else {
                    n.put("d", System.currentTimeMillis() - noteBufferTime);
                }
                noteBuffer.put(n);
            } catch (JSONException e) {
                e.printStackTrace();
            }



        }
    }

    public void stopNote(String note){
        if(isConnected()) {
            try {
                JSONObject n = new JSONObject().put("n", note).put("s", 1);
                if(noteBufferTime == 0) {
                    noteBufferTime = System.currentTimeMillis();
                } else {
                    n.put("d", System.currentTimeMillis() - noteBufferTime);
                }
                noteBuffer.put(n);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    public JSONObject mergeJSON(JSONObject json, JSONObject in) {
        JSONArray names = in.names();
        int len = names.length();
        for(int i = 0; i < len; i++) {
            try {
                String name = names.getString(i);
                json.put(name, in.get(name));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return json;
    }
}
