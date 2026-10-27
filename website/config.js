// Where this website finds a Cinder node.
//
// Normally you don't need to touch this: your node phone posts its current
// address, signed with your network key, and the site picks it up.
export const DISCOVERY = {
  relay: 'https://ntfy.sh',
  topic: 'cinder-d24ebbd9527ffd25b672',
  key: '04f832239da18bcb77fff34722da7e41c4cc0c2d96240a1882336e34fc33d9b90e5e882ab62eb65bcc02e21a96ccf88f9928553f37ebb279f24b8aecfb1aed1b64',
};

// If you run a node at a fixed address (your own domain), list it here and
// the site tries it first.
export const NODES = [
  // 'https://node.yourdomain.com',
];
