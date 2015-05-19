GodotGPS
========

This is the Google Play Service module for Godot Engine (https://github.com/okamstudio/godot)
- Android only
- Leaderboard
- Achievements

How to use
----------
Drop the "googleplayservice" directory inside the "modules" directory on the Godot source.

In android/AndroidManifestChunk.xml modify:
```
<meta-data android:name="com.google.android.gms.games.APP_ID"
  android:value="\ 012345678901" /> 
```
Replace your APP_ID value, it must begin with "\ ".

Yes there is uncomfortable because each apps have a unique value, I haven't found better solution yet.

```
  <meta-data android:name="com.google.android.gms.version"
    android:value="@integer/google_play_services_version" />
```
If your other module had this meta-data (such as Admob module) so delete this.

Recompile

In your project:

file engine.cfg add
```
  [android]
    modules="com/android/godot/GodotGPS"
``` 
If you use multiple modules add with comma (without space) such as
```
  [android]
    modules="com/android/godot/GodotAdMob,com/android/godot/GodotGPS"
```
Export->Target->Android

	Options:
		Custom Package:
			- place your apk from build
		Permissions on:
			- Access Network State
			- Internet

To refer to the callback functions use setInstanceID and then just write the callback function as in example below:
```
func initGPS():
	if(Globals.has_singleton("GooglePlayService")):
		gps	= Globals.get_singleton("GooglePlayService")
		gps.setInstanceID(get_instance_ID())
        gps.init()

func _on_connected():
	print("connected")            


API Reference
-------------

The following methods are available:
```
  void init()
  void signIn()
  void signOut()
	
  int getStatus()
    return:
    0 = not connect
    1 = connecting
    2 = connected
  
  void lbSubmit(String id, int score)
    id = Leaderboard ID
    score = score value
  
  void lbShow(String id)
    id = Leaderboard ID

  void acUnlock(String id)
    id = Achievement Id to Unlock
    
  void acIncrement(String id, int val)
    id = Achievement Id to Unlock
    val = value of increment
  
  void acDisplay()
  
  void setInstanceID(int instanceId)
  
  void seeInvitations()
  
  void getProfileInfo()
  
  startQuickGame()
  
  void selectOpponents(int min, int max)
  
  void acceptInviteToRoom(int invitationId)
  
  void broadcastUnreliableMessage(String message)
```

Callback Functions
-------------
```
_on_connected()

_on_selected_player_ui_cancelled()

_on_getCurrentPerson(status, name, photo, profile, email)

_on_invitation_cancelled()

_on_invitation_succeeded()

_on_game_start()

_on_invitation_recieved(invitedFrom, invitationId)

_on_invitation_removed()

_on_connected_to_room(RoomId, Participants, MyId)

_on_left_room(statusCode, roomId)

_on_disconnected_from_room(room)

_on_updatePeerScoresDisplay()

_on_leave_room(roomId)

_on_received_message(message)

```
License
-------------
MIT license
