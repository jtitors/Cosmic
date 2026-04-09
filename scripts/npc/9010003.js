item = 1102041;
function start() {
    if (cm.canHold(1102041)) {
        cm.sendNext("enjoy your #1102041");
        cm.gainItem(1102041, 1);
    } else {
        cm.sendOk("Your inventory is full");
    }
    cm.dispose();
}

function action(mode, type, selection) {
    cm.dispose();
}