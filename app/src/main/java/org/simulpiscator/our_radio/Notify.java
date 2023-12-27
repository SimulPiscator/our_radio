package org.simulpiscator.our_radio;

import com.google.gson.Gson;

public class Notify {
   String[] notify;
   static final Gson gson = new Gson(); // note Gson does not work with proguard enabled
   Notify() {}
   void fromJson(String s) {
      Notify n = gson.fromJson(s, Notify.class);
      notify = n.notify;
   }
   String toJson() {
      return gson.toJson(this);
   }
};
