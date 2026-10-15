// Packaging preview. Local care and server pairing arrive in later issues.
const byId = id => document.getElementById(id);
byId('garden').hidden = false;
byId('add-open').hidden = true;
byId('help-open').hidden = true;
byId('sign-out').hidden = true;
byId('care-summary').textContent = 'Android shell preview';
byId('page-message').textContent = 'The app is bundled and opens without a connection. Plant care is coming in a later update.';
document.querySelector('.heading .muted').textContent = 'A preview of your garden. No plants or care actions are saved by this version.';
const example = byId('plant-template').content.firstElementChild.cloneNode(true);
example.querySelector('.plant-name').textContent = 'Example fern';
example.querySelector('.species').textContent = 'Sample only';
example.querySelector('.due').textContent = 'Check the soil before watering';
example.querySelector('.last').textContent = 'Synthetic preview — no care history';
example.querySelector('img').hidden = true;
for (const button of example.querySelectorAll('button')) button.hidden = true;
byId('plants').append(example);
