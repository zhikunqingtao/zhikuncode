(function () {
  var button = document.getElementById('activate');
  var result = document.getElementById('result');
  var input = document.getElementById('name');
  window.__lastActivation = '';
  button.addEventListener('click', function () {
    window.__lastActivation = 'button';
    result.textContent = 'activated:' + (input.value || '');
  });
})();
