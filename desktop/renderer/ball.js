(function () {
  'use strict';

  var ball = document.getElementById('ball');
  var down = false;
  var moved = false;
  var startX = 0;
  var startY = 0;

  ball.addEventListener('mousedown', function (event) {
    if (event.button !== 0) return;
    down = true;
    moved = false;
    startX = event.screenX;
    startY = event.screenY;
    window.pocket.dragStart();
    event.preventDefault();
  });

  window.addEventListener('mousemove', function (event) {
    if (!down) return;
    if (Math.abs(event.screenX - startX) + Math.abs(event.screenY - startY) > 4) {
      moved = true;
      ball.classList.add('dragging');
    }
  });

  window.addEventListener('mouseup', function () {
    if (!down) return;
    down = false;
    ball.classList.remove('dragging');
    window.pocket.dragEnd(moved);
  });

  ball.addEventListener('contextmenu', function (event) {
    event.preventDefault();
    window.pocket.ballMenu();
  });
})();
