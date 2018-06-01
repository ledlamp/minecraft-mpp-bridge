package com.multiplayerpiano.multiplayerpiano.MPP;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Created by phree on 6/17/15.
 */
public class EventEmitter {
    private HashMap<String, ArrayList<Callback>> _events = new HashMap<>();

    public void on(String evtn, Callback cb) {
        if(!_events.containsKey(evtn)) _events.put(evtn, new ArrayList<Callback>());
        _events.get(evtn).add(cb);
    }

    public void off(String evtn, Callback cb) {
        if(!_events.containsKey(evtn)) return;
        int idx = _events.get(evtn).indexOf(cb);
        if(idx < 0) return;
        _events.get(evtn).remove(idx);
    }

    public void emit(String evtn, Object... args) {
        if(!_events.containsKey(evtn)) return;
        ArrayList<Callback> cbs = (ArrayList<Callback>)_events.get(evtn).clone();
        if(cbs.size() < 1) return;
        for(int i = 0; i < cbs.size(); i++) cbs.get(i).call(args);
    }
}
