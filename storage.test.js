const { test, describe, before, after, beforeEach } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require('@firebase/rules-unit-testing');

const PROJECT_ID = 'el-batal-express-956ae';
const VALID_JPEG_BYTES = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01]);

let testEnv;

function parseHostPort(envVal, defaultHost, defaultPort) {
  const raw = envVal || `${defaultHost}:${defaultPort}`;
  const [host, portStr] = raw.split(':');
  return { host: host || defaultHost, port: Number(portStr || defaultPort) };
}

async function seedFirestoreIdentities() {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection('admins').doc('admin_1').set({
      role: 'admin',
      email: 'admin@elbatal.com',
      emailVerified: true,
      status: 'active',
    });
    await db.collection('merchants').doc('merchant_1').set({
      uid: 'merchant_1',
      accountType: 'merchant',
      role: 'merchant',
      name: 'مطعم البطل',
      email: 'merchant1@elbatal.com',
      status: 'active',
    });
  });
}

describe('El-Batal Express Firebase Storage Security Rules — Captain Registration & Document Privacy', () => {
  before(async () => {
    const fsAddr = parseHostPort(process.env.FIRESTORE_EMULATOR_HOST, '127.0.0.1', 8085);
    const stAddr = parseHostPort(process.env.STORAGE_EMULATOR_HOST, '127.0.0.1', 9199);

    testEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: {
        host: fsAddr.host,
        port: fsAddr.port,
        rules: fs.readFileSync(path.join(__dirname, 'firestore.rules'), 'utf8'),
      },
      storage: {
        host: stAddr.host,
        port: stAddr.port,
        rules: fs.readFileSync(path.join(__dirname, 'storage.rules'), 'utf8'),
      },
    });
  });

  beforeEach(async () => {
    await testEnv.clearFirestore();
    await testEnv.clearStorage();
    await seedFirestoreIdentities();
  });

  after(async () => {
    if (testEnv) {
      await testEnv.cleanup();
    }
  });

  test('1. Newly authenticated captain can upload profile, ID front, ID back, and optional driving license under own captains/{uid} folder', async () => {
    const uid = 'cap_upload_001';
    const storage = testEnv.authenticatedContext(uid, {
      email: 'cap001@elbatal.com',
      email_verified: false,
    }).storage();

    const meta = { contentType: 'image/jpeg' };
    await assertSucceeds(storage.ref(`captains/${uid}/profile/profile.jpg`).put(VALID_JPEG_BYTES, meta));
    await assertSucceeds(storage.ref(`captains/${uid}/documents/id_front.jpg`).put(VALID_JPEG_BYTES, meta));
    await assertSucceeds(storage.ref(`captains/${uid}/documents/id_back.jpg`).put(VALID_JPEG_BYTES, meta));
    await assertSucceeds(storage.ref(`captains/${uid}/documents/license.jpg`).put(VALID_JPEG_BYTES, meta));

    // Owning captain can read back own uploaded documents
    await assertSucceeds(storage.ref(`captains/${uid}/documents/id_front.jpg`).getDownloadURL());
    await assertSucceeds(storage.ref(`captains/${uid}/documents/license.jpg`).getDownloadURL());
  });

  test('2. Unauthenticated users and other captains are strictly denied uploading to or reading another captain folder', async () => {
    const ownerUid = 'cap_owner_002';
    const attackerUid = 'cap_attacker_003';
    const ownerStorage = testEnv.authenticatedContext(ownerUid, {
      email: 'owner@elbatal.com',
      email_verified: false,
    }).storage();
    const attackerStorage = testEnv.authenticatedContext(attackerUid, {
      email: 'attacker@elbatal.com',
      email_verified: true,
    }).storage();
    const anonStorage = testEnv.unauthenticatedContext().storage();

    const meta = { contentType: 'image/jpeg' };
    await assertSucceeds(ownerStorage.ref(`captains/${ownerUid}/documents/id_front.jpg`).put(VALID_JPEG_BYTES, meta));
    await assertSucceeds(ownerStorage.ref(`captains/${ownerUid}/profile/profile.jpg`).put(VALID_JPEG_BYTES, meta));

    // Unauthenticated denied upload & read
    await assertFails(anonStorage.ref(`captains/${ownerUid}/documents/id_back.jpg`).put(VALID_JPEG_BYTES, meta));
    await assertFails(anonStorage.ref(`captains/${ownerUid}/documents/id_front.jpg`).getDownloadURL());

    // Another captain denied upload to victim folder and denied reading victim ID/profile
    await assertFails(attackerStorage.ref(`captains/${ownerUid}/documents/id_front.jpg`).put(VALID_JPEG_BYTES, meta));
    await assertFails(attackerStorage.ref(`captains/${ownerUid}/documents/id_back.jpg`).put(VALID_JPEG_BYTES, meta));
    await assertFails(attackerStorage.ref(`captains/${ownerUid}/documents/license.jpg`).put(VALID_JPEG_BYTES, meta));
    await assertFails(attackerStorage.ref(`captains/${ownerUid}/profile/profile.jpg`).put(VALID_JPEG_BYTES, meta));
    await assertFails(attackerStorage.ref(`captains/${ownerUid}/documents/id_front.jpg`).getDownloadURL());
    await assertFails(attackerStorage.ref(`captains/${ownerUid}/profile/profile.jpg`).getDownloadURL());
  });

  test('3. Rejects non-image MIME types, disallowed document filenames, and merchant uploads/reads of captain documents; allows verified admin read', async () => {
    const uid = 'cap_privacy_004';
    const capStorage = testEnv.authenticatedContext(uid, {
      email: 'cap004@elbatal.com',
      email_verified: false,
    }).storage();
    const merchantStorage = testEnv.authenticatedContext('merchant_1', {
      email: 'merchant1@elbatal.com',
      email_verified: true,
    }).storage();
    const adminStorage = testEnv.authenticatedContext('admin_1', {
      email: 'admin@elbatal.com',
      email_verified: true,
    }).storage();

    // Upload valid ID front first
    await assertSucceeds(
      capStorage.ref(`captains/${uid}/documents/id_front.jpg`).put(VALID_JPEG_BYTES, { contentType: 'image/jpeg' })
    );

    // Non-image contentType rejected
    await assertFails(
      capStorage.ref(`captains/${uid}/documents/id_back.jpg`).put(Buffer.from('not an image'), { contentType: 'text/plain' })
    );

    // Arbitrary document filename outside allowlist rejected
    await assertFails(
      capStorage.ref(`captains/${uid}/documents/arbitrary.jpg`).put(VALID_JPEG_BYTES, { contentType: 'image/jpeg' })
    );

    // Merchant cannot upload as captain or read captain ID documents
    await assertFails(
      merchantStorage.ref('captains/merchant_1/documents/id_front.jpg').put(VALID_JPEG_BYTES, { contentType: 'image/jpeg' })
    );
    await assertFails(merchantStorage.ref(`captains/${uid}/documents/id_front.jpg`).getDownloadURL());

    // Verified active admin CAN read captain documents for review in Admin Dashboard
    await assertSucceeds(adminStorage.ref(`captains/${uid}/documents/id_front.jpg`).getDownloadURL());
  });
});
