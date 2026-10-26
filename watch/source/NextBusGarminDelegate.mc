import Toybox.Lang;
import Toybox.WatchUi;

class NextBusGarminDelegate extends WatchUi.BehaviorDelegate {

    function initialize() {
        BehaviorDelegate.initialize();
    }

    function onMenu() as Boolean {
        WatchUi.pushView(new Rez.Menus.MainMenu(), new NextBusGarminMenuDelegate(), WatchUi.SLIDE_UP);
        return true;
    }

}