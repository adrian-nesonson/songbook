var song = document.getElementById("song");
var fontSize = 100;
var biggerButton = document.getElementById("biggerButton");
biggerButton.addEventListener("click", function (e) {
    fontSize += 10;
    song.style.fontSize = fontSize + "%";
});
var smallerButton = document.getElementById("smallerButton");
smallerButton.addEventListener("click", function (e) {
    fontSize -= 10;
    song.style.fontSize = fontSize + "%";
});
//# sourceMappingURL=view.js.map