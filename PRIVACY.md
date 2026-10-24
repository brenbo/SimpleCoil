# SimpleCoil Privacy Policy

**Effective date:** October 23, 2026

SimpleCoil is a free, open-source app by Ethan Yonker that connects to Recoil laser tag blasters and runs laser tag games over a local network. This policy explains what information the app uses, where it goes, and how long it is kept.

**In short:** SimpleCoil has no accounts, no ads, no analytics and no tracking. It never sends your information to the developer. Players' phones don't connect to the internet: information only travels between devices on the local Wi-Fi network during a game you join, and only for as long as the game needs it. The one exception is the dedicated server's field map, which downloads aerial photos from MapTiler (or, in builds without a MapTiler key, the U.S. Geological Survey) when the host asks it to.

## Information the app uses

### Player name and player ID
You choose a player name, and the app keeps it on your device along with your player ID. When you join a network game, the game server and the other players in that game see your name, and your score shows on the scoreboard.

### Location (GPS)
SimpleCoil asks for precise location only when you join a dedicated server that uses it: the GPS map, or a field with a border, respawn points, capture points, drops or shot distances. While that game is running and the app is open, your position is sent to the game server whenever it changes, and every few seconds while you stand still. The server uses it to tell who stands on a capture point, to settle who claims a drop, to measure how far each elimination was for the Longest Shot award, and, if the host turned on GPS sharing, to show you on other players' maps: about once a second, it passes your position on to your teammates only or to everyone, depending on how the host set up the game. A UAV power-up briefly shows the player who earned it where the other team is.

Your location is held only in memory, on the game server and on the other players' devices, while the game is running. For the post-game map, the server keeps, in memory and until the next game starts, where each measured elimination happened and, unless the host turned it down to kills only or off, everyone's position every few seconds during the game, so the players in that game can see the kills, a heat map and a replay afterwards. Nothing is saved to storage, and none of it is sent anywhere else. SimpleCoil doesn't use location in the background. If you deny location access, you can still play; you just won't appear on the map.

On Android 11 and older, Android also requires location permission to scan for Bluetooth devices. There, SimpleCoil asks for it when you connect your blaster, and uses it only to find the blaster.

### Field map (dedicated server only)
The host of a dedicated server can download an aerial photo of the playing field. The SimpleCoil builds on Google Play and GitHub get it from MapTiler, a map provider based in Switzerland; a build made from the source code without a MapTiler key gets it from the U.S. Geological Survey (USGS), a U.S. government agency. To find the field, the server's field map screen loads map images for the area the host is looking at, and the download asks for a photo of the chosen area. Like any website, the provider receives the server phone's internet (IP) address and the area requested, under its own privacy policy (MapTiler: <https://www.maptiler.com/privacy-policy/>; USGS: <https://www.usgs.gov/policies-and-notices>). The server's "My location" button uses the server phone's location only to move the map, and "Walk" records the path the host walks with the server phone to draw the field's border. Neither is sent to the map provider; the border goes only to the phones that join, as part of the game.

The server keeps the photo on its device and sends it to the phones that join its games over the local Wi-Fi network. Each phone keeps the latest field it was sent until another server sends a different one. Players' phones never contact the map provider or anyone else on the internet.

### Bluetooth
SimpleCoil uses Bluetooth to find and connect to your blaster. The app remembers your blaster's Bluetooth address so it can reconnect to it. When you join a network game, that address is sent to the game server so the server can recognize your blaster if you rejoin.

### Local network
Network games run over your local Wi-Fi network. The app finds game servers on that network and connects to them directly. Everything a game sends between phones, including locations and voice chat, is encrypted. The game server and the other players can see each device's local network (IP) address, and the scoreboard shows it. These addresses only work inside your local network.

### Camera
The camera is used only to scan QR codes, such as the code that pairs a phone with a blaster. Images are processed on your device and are never saved or sent anywhere.

### Notifications
SimpleCoil shows a notification while it keeps your blaster or a network game connected in the background. Notifications contain no personal information beyond the game status.

## Information stored on your device
The app saves its settings on your device: your player name and ID, game settings, the dedicated server's settings, your saved blaster, and the field map photo described above. If you have Android backup turned on, these settings may be included in your device backup through Google's backup service, under your Google account. Nothing else from the app is backed up.

## Sharing
SimpleCoil doesn't sell, rent or share your information with anyone. The only information that leaves your device is the game information described above, which goes only to the game server and the other players in a game you choose to join, on your local network, and, on a dedicated server whose host downloads a field map, the requests to the map provider described under Field map.

## Data retention and deletion
Game information, including scores, player lists and locations, lasts only as long as the game. It is gone when the game server stops. To delete the settings saved on your device, clear the app's storage in Android settings or uninstall the app.

## Children
SimpleCoil isn't directed at children under 13 and doesn't knowingly collect personal information from them. The app doesn't collect information for the developer from anyone.

## Changes to this policy
If this policy changes, the updated version will be posted at this address with a new effective date.

## Contact
Questions about this policy can be asked by opening an issue on the SimpleCoil GitHub project: <https://github.com/Dees-Troy/SimpleCoil/issues>
