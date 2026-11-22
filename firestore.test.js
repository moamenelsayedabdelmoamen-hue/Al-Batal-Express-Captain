const { test, describe, before, after, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require('@firebase/rules-unit-testing');

const PROJECT_ID = 'el-batal-express-956ae';

let testEnv;

function parseEmulatorHost() {
  const raw = process.env.FIRESTORE_EMULATOR_HOST || '127.0.0.1:8085';
  const [host, portStr] = raw.split(':');
  return { host: host || '127.0.0.1', port: Number(portStr || 8085) };
}

/**
 * Exact 34-field registration document produced by CaptainRepository.buildNewCaptainDocumentData(...)
 */
function buildFullCaptainRegistrationPayload(uid, overrides = {}) {
  return {
    uid,
    name: 'كابتن كريم محمود',
    fullName: 'كابتن كريم محمود',
    driver_name: 'كابتن كريم محمود',
    email: 'kareem.captain@elbatal.com',
    phone: '01099887766',
    phoneNumber: '01099887766',
    address: 'القاهرة - مدينة نصر',
    vehicleType: 'موتوسيكل',
    licensePlate: '',
    personalImage: `captains/${uid}/profile/profile.jpg`,
    profileImagePath: `captains/${uid}/profile/profile.jpg`,
    idCardFront: `captains/${uid}/documents/id_front.jpg`,
    idFrontImagePath: `captains/${uid}/documents/id_front.jpg`,
    idCardBack: `captains/${uid}/documents/id_back.jpg`,
    idBackImagePath: `captains/${uid}/documents/id_back.jpg`,
    licenseImagePath: `captains/${uid}/documents/license.jpg`,
    idDocumentsComplete: true,
    accountType: 'captain',
    role: 'captain',
    status: 'pending',
    accountStatus: 'pending',
    isApproved: false,
    emailVerified: false,
    isEmailVerified: false,
    availabilityStatus: 'offline',
    presenceStatus: 'offline',
    shiftStatus: 'outside_shift',
    isOnline: false,
    isLoggedIn: false,
    totalOrdersCount: 0,
    walletBalance: 0.0,
    rating: 5.0,
    avatarUri: `captains/${uid}/profile/profile.jpg`,
    avatarPresetIndex: 0,
    createdAt: new Date(),
    updatedAt: Date.now(),
    ...overrides,
  };
}

async function seedBaseData() {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();

    await db.collection('admins').doc('admin_1').set({
      role: 'admin',
      email: 'admin@elbatal.com',
      emailVerified: true,
      status: 'active',
    });

    await db.collection('admins').doc('admin_suspended').set({
      role: 'admin',
      email: 'suspended@elbatal.com',
      emailVerified: true,
      status: 'suspended',
    });

    await db.collection('merchants').doc('merchant_1').set({
      uid: 'merchant_1',
      accountType: 'merchant',
      role: 'merchant',
      name: 'مطعم البطل الأول',
      email: 'merchant1@elbatal.com',
      phone: '01000000001',
      address: 'مدينة نصر',
      status: 'active',
      isOpen: true,
    });

    await db.collection('merchants').doc('merchant_2').set({
      uid: 'merchant_2',
      accountType: 'merchant',
      role: 'merchant',
      name: 'مطعم الشرق الثاني',
      email: 'merchant2@elbatal.com',
      phone: '01000000002',
      address: 'المعادي',
      status: 'active',
      isOpen: true,
    });

    await db.collection('captains').doc('captain_existing').set({
      uid: 'captain_existing',
      name: 'كابتن أحمد',
      fullName: 'كابتن أحمد',
      phone: '01011111111',
      email: 'captain1@elbatal.com',
      accountType: 'captain',
      role: 'captain',
      status: 'active',
      presenceStatus: 'on_trip',
      walletBalance: 120.0,
      rating: 4.9,
      totalOrdersCount: 15,
      activeMerchantId: 'merchant_1',
      profileImagePath: 'captains/captain_existing/profile/profile.jpg',
    });

    // Legacy captain created before `accountType` was introduced
    await db.collection('captains').doc('captain_legacy').set({
      uid: 'captain_legacy',
      name: 'كابتن قديم',
      fullName: 'كابتن قديم',
      phone: '01033333333',
      email: 'legacy@elbatal.com',
      role: 'captain',
      status: 'active',
      walletBalance: 75.0,
    });

    await db.collection('orders').doc('order_m1_c1').set({
      orderNumber: 'EXP-101',
      status: 'picked_up',
      captainId: 'captain_existing',
      merchantId: 'merchant_1',
      customerId: 'user_1',
      deliveryFee: 35,
    });

    await db.collection('orders').doc('order_pending_offer').set({
      orderNumber: 'EXP-102',
      status: 'pending',
      captainId: '',
      offeredCaptainId: 'captain_existing',
      merchantId: 'merchant_1',
      customerId: 'user_1',
      deliveryFee: 30,
    });

    await db.collection('settings').doc('manual_subscription_config').set({
      vodafoneCashNumber: '01099887766',
      isAcceptingSubscriptions: true,
    });

    await db.collection('manual_subscription_requests').doc('req_m1').set({
      merchantId: 'merchant_1',
      planId: 'plan_4_captains',
      status: 'pending_review',
    });
  });
}

describe('El-Batal Express Firestore Security Rules — Captain Registration & Non-Regression', () => {
  before(async () => {
    const { host, port } = parseEmulatorHost();
    testEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: {
        host,
        port,
        rules: fs.readFileSync(path.join(__dirname, 'firestore.rules'), 'utf8'),
      },
    });
  });

  beforeEach(async () => {
    await testEnv.clearFirestore();
    await seedBaseData();
  });

  after(async () => {
    if (testEnv) {
      await testEnv.cleanup();
    }
  });

  test('1. New captain self-registration succeeds with full 34-field payload (all 4 photos) and can read own document back', async () => {
    const uid = 'new_captain_uid_001';
    const db = testEnv.authenticatedContext(uid, {
      email: 'kareem.captain@elbatal.com',
      email_verified: false,
    }).firestore();

    const payload = buildFullCaptainRegistrationPayload(uid);
    await assertSucceeds(db.collection('captains').doc(uid).set(payload));

    const snap = await assertSucceeds(db.collection('captains').doc(uid).get());
    assert.equal(snap.exists, true);
    assert.equal(snap.data().uid, uid);
    assert.equal(snap.data().accountType, 'captain');
    assert.equal(snap.data().status, 'pending');
    assert.equal(snap.data().vehicleType, 'موتوسيكل');
    assert.equal(snap.data().idFrontImagePath, `captains/${uid}/documents/id_front.jpg`);
    assert.equal(snap.data().idBackImagePath, `captains/${uid}/documents/id_back.jpg`);
    assert.equal(snap.data().licenseImagePath, `captains/${uid}/documents/license.jpg`);
  });

  test('2. New captain self-registration succeeds without optional photos (empty image paths)', async () => {
    const uid = 'new_captain_uid_002';
    const db = testEnv.authenticatedContext(uid, {
      email: 'minimal.captain@elbatal.com',
      email_verified: false,
    }).firestore();

    const payload = buildFullCaptainRegistrationPayload(uid, {
      email: 'minimal.captain@elbatal.com',
      personalImage: '',
      profileImagePath: '',
      idCardFront: '',
      idFrontImagePath: '',
      idCardBack: '',
      idBackImagePath: '',
      licenseImagePath: '',
      avatarUri: '',
      idDocumentsComplete: false,
    });
    await assertSucceeds(db.collection('captains').doc(uid).set(payload));
  });

  test('3. Unauthenticated and cross-captain create/read/update attempts are strictly denied', async () => {
    const anonDb = testEnv.unauthenticatedContext().firestore();
    const attackerUid = 'attacker_uid_999';
    const victimUid = 'victim_uid_888';
    const attackerDb = testEnv.authenticatedContext(attackerUid, {
      email: 'attacker@elbatal.com',
      email_verified: false,
    }).firestore();

    // Unauthenticated cannot create or read captains
    await assertFails(
      anonDb.collection('captains').doc('anon_cap').set(buildFullCaptainRegistrationPayload('anon_cap'))
    );
    await assertFails(anonDb.collection('captains').doc('captain_existing').get());
    await assertFails(anonDb.collection('captains').get());

    // Attacker cannot create document under another UID (even with victim UID in payload or attacker UID in payload)
    await assertFails(
      attackerDb.collection('captains').doc(victimUid).set(buildFullCaptainRegistrationPayload(victimUid))
    );
    await assertFails(
      attackerDb.collection('captains').doc(victimUid).set(buildFullCaptainRegistrationPayload(attackerUid))
    );

    // Attacker cannot read or update another captain
    await assertFails(attackerDb.collection('captains').doc('captain_existing').get());
    await assertFails(attackerDb.collection('captains').doc('captain_existing').update({ name: 'Hacked' }));
  });

  test('4. Privilege, financial, verification, and invalid Storage path tampering during captain registration is blocked', async () => {
    const uid = 'tamper_captain_uid';
    const db = testEnv.authenticatedContext(uid, {
      email: 'tamper@elbatal.com',
      email_verified: false,
    }).firestore();

    // Privilege / status / admin escalation
    await assertFails(db.collection('captains').doc(uid).set(buildFullCaptainRegistrationPayload(uid, { role: 'admin' })));
    await assertFails(db.collection('captains').doc(uid).set(buildFullCaptainRegistrationPayload(uid, { status: 'active' })));
    await assertFails(db.collection('captains').doc(uid).set(buildFullCaptainRegistrationPayload(uid, { accountType: 'admin' })));
    await assertFails(db.collection('captains').doc(uid).set(buildFullCaptainRegistrationPayload(uid, { isAdmin: true })));
    await assertFails(db.collection('captains').doc(uid).set(buildFullCaptainRegistrationPayload(uid, { isApproved: true })));

    // Financial tampering
    await assertFails(db.collection('captains').doc(uid).set(buildFullCaptainRegistrationPayload(uid, { walletBalance: 5000 })));
    await assertFails(db.collection('captains').doc(uid).set(buildFullCaptainRegistrationPayload(uid, { totalOrdersCount: 99 })));

    // Forged emailVerified=true with unverified token
    await assertFails(db.collection('captains').doc(uid).set(buildFullCaptainRegistrationPayload(uid, { emailVerified: true })));
    await assertFails(db.collection('captains').doc(uid).set(buildFullCaptainRegistrationPayload(uid, { isEmailVerified: true })));

    // Cross-captain Storage path spoofing & raw local content:// or file:// URI injection
    await assertFails(
      db.collection('captains').doc(uid).set(
        buildFullCaptainRegistrationPayload(uid, {
          idFrontImagePath: 'captains/captain_existing/documents/id_front.jpg',
        })
      )
    );
    await assertFails(
      db.collection('captains').doc(uid).set(
        buildFullCaptainRegistrationPayload(uid, {
          profileImagePath: 'content://media/external/images/media/42',
        })
      )
    );
    await assertFails(
      db.collection('captains').doc(uid).set(
        buildFullCaptainRegistrationPayload(uid, {
          licenseImagePath: 'file:///data/user/0/com.example/cache/local.jpg',
        })
      )
    );

    // Extra disallowed key injection
    await assertFails(
      db.collection('captains').doc(uid).set(
        buildFullCaptainRegistrationPayload(uid, {
          activeMerchantId: 'merchant_1',
        })
      )
    );
  });

  test('5. Legacy and existing captains can still read & update safe fields, while sensitive fields remain locked', async () => {
    const existingDb = testEnv.authenticatedContext('captain_existing', {
      email: 'captain1@elbatal.com',
      email_verified: true,
    }).firestore();
    const legacyDb = testEnv.authenticatedContext('captain_legacy', {
      email: 'legacy@elbatal.com',
      email_verified: true,
    }).firestore();

    // Existing captain safe update + verified email flag sync
    await assertSucceeds(
      existingDb.collection('captains').doc('captain_existing').update({
        presenceStatus: 'available',
        isOnline: true,
        emailVerified: true,
        isEmailVerified: true,
        profileImagePath: 'captains/captain_existing/profile/profile.jpg',
      })
    );
    // Existing captain blocked on wallet/status/role/cross-path tampering
    await assertFails(existingDb.collection('captains').doc('captain_existing').update({ walletBalance: 9999 }));
    await assertFails(existingDb.collection('captains').doc('captain_existing').update({ status: 'blocked' }));
    await assertFails(existingDb.collection('captains').doc('captain_existing').update({ role: 'admin' }));
    await assertFails(
      existingDb.collection('captains').doc('captain_existing').update({
        idFrontImagePath: 'captains/other_captain/documents/id_front.jpg',
      })
    );

    // Legacy captain (no accountType field, role == 'captain') can read & update safe operational fields
    await assertSucceeds(legacyDb.collection('captains').doc('captain_legacy').get());
    await assertSucceeds(
      legacyDb.collection('captains').doc('captain_legacy').update({
        isOnline: true,
        presenceStatus: 'available',
        vehicleType: 'موتوسيكل',
      })
    );
    await assertFails(legacyDb.collection('captains').doc('captain_legacy').update({ walletBalance: 999 }));
    await assertFails(legacyDb.collection('captains').doc('captain_legacy').update({ status: 'approved' }));
  });

  test('6. Merchant registration, isolation, orders, subscriptions, and verified admin access remain intact', async () => {
    const merch1Db = testEnv.authenticatedContext('merchant_1', {
      email: 'merchant1@elbatal.com',
      email_verified: true,
    }).firestore();
    const merch2Db = testEnv.authenticatedContext('merchant_2', {
      email: 'merchant2@elbatal.com',
      email_verified: true,
    }).firestore();
    const newMerchUid = 'merchant_new_001';
    const newMerchDb = testEnv.authenticatedContext(newMerchUid, {
      email: 'newmerchant@elbatal.com',
      email_verified: false,
    }).firestore();
    const adminDb = testEnv.authenticatedContext('admin_1', {
      email: 'admin@elbatal.com',
      email_verified: true,
    }).firestore();
    const suspendedAdminDb = testEnv.authenticatedContext('admin_suspended', {
      email: 'suspended@elbatal.com',
      email_verified: true,
    }).firestore();

    // New merchant self-registration still works
    await assertSucceeds(
      newMerchDb.collection('merchants').doc(newMerchUid).set({
        uid: newMerchUid,
        name: 'مطعم جديد',
        email: 'newmerchant@elbatal.com',
        phone: '01044556677',
        address: 'الجيزة',
        category: 'مطاعم',
        accountType: 'merchant',
        role: 'merchant',
        emailVerified: false,
        status: 'pending',
        isSubscribed: false,
        subscriptionStatus: 'unsubscribed',
        maxCaptains: 0,
        planId: 0,
      })
    );

    // Existing merchant CANNOT register itself as a captain
    await assertFails(
      merch1Db.collection('captains').doc('merchant_1').set(
        buildFullCaptainRegistrationPayload('merchant_1', { email: 'merchant1@elbatal.com' })
      )
    );

    // Merchant order & linked captain isolation preserved
    await assertSucceeds(merch1Db.collection('captains').doc('captain_existing').get());
    await assertFails(merch2Db.collection('captains').doc('captain_existing').get());
    await assertSucceeds(merch1Db.collection('orders').doc('order_m1_c1').get());
    await assertFails(merch2Db.collection('orders').doc('order_m1_c1').get());

    // Manual subscriptions & settings preserved
    await assertSucceeds(merch1Db.collection('settings').doc('manual_subscription_config').get());
    await assertSucceeds(merch1Db.collection('manual_subscription_requests').doc('req_m1').get());
    await assertFails(merch2Db.collection('manual_subscription_requests').doc('req_m1').get());

    // Verified active admin retains management access; suspended admin denied
    await assertSucceeds(adminDb.collection('captains').doc('captain_existing').update({ walletBalance: 200 }));
    await assertFails(suspendedAdminDb.collection('captains').doc('captain_existing').update({ status: 'blocked' }));
  });

  test('7. Captain Account Approval System: signup enforces pending/unapproved, blocks self-approval & unapproved ops, unlocks after admin approval', async () => {
    const uid = 'pending_approval_cap_001';
    const capDb = testEnv.authenticatedContext(uid, {
      email: 'pending.cap@elbatal.com',
      email_verified: true,
    }).firestore();
    const adminDb = testEnv.authenticatedContext('admin_1', {
      email: 'admin@elbatal.com',
      email_verified: true,
    }).firestore();

    // 7a. Signup MUST fail if accountStatus != 'pending', isApproved != false, createdAt not timestamp, or isOnline == true
    await assertFails(capDb.collection('captains').doc(uid).set(buildFullCaptainRegistrationPayload(uid, { accountStatus: 'approved' })));
    await assertFails(capDb.collection('captains').doc(uid).set(buildFullCaptainRegistrationPayload(uid, { isApproved: true })));
    await assertFails(capDb.collection('captains').doc(uid).set(buildFullCaptainRegistrationPayload(uid, { createdAt: Date.now() })));
    await assertFails(capDb.collection('captains').doc(uid).set(buildFullCaptainRegistrationPayload(uid, { isOnline: true })));
    await assertFails(capDb.collection('captains').doc(uid).set(buildFullCaptainRegistrationPayload(uid, { rejectionReason: 'none' })));

    // 7b. Valid pending signup succeeds
    await assertSucceeds(capDb.collection('captains').doc(uid).set(buildFullCaptainRegistrationPayload(uid, {
      email: 'pending.cap@elbatal.com',
    })));

    // Seed an order offered to this captain and a schedule + wallet subdoc to verify operational gating
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const sdb = ctx.firestore();
      await sdb.collection('orders').doc('ord_for_pending_cap').set({
        orderNumber: 'EXP-999',
        status: 'pending',
        captainId: '',
        offeredCaptainId: uid,
        merchantId: 'merchant_1',
        customerId: 'user_1',
        deliveryFee: 25,
      });
      await sdb.collection('captain_schedules').doc(uid).set({
        captainId: uid,
        isPaused: false,
        weeklySchedule: {},
      });
      await sdb.collection('captains').doc(uid).collection('transactions').doc('tx_1').set({
        amount: 25,
      });
    });

    // 7c. Pending captain CAN read own captains/{uid} doc (to see approval status) and sync emailVerified / offline state
    const ownSnap = await assertSucceeds(capDb.collection('captains').doc(uid).get());
    assert.equal(ownSnap.data().accountStatus, 'pending');
    assert.equal(ownSnap.data().isApproved, false);
    await assertSucceeds(capDb.collection('captains').doc(uid).update({
      emailVerified: true,
      isEmailVerified: true,
      isOnline: false,
      presenceStatus: 'offline',
    }));

    // 7d. Pending captain CANNOT self-approve, change accountStatus/status/rejectionReason, go online, send GPS, or read orders/schedules/wallet
    await assertFails(capDb.collection('captains').doc(uid).update({ isApproved: true }));
    await assertFails(capDb.collection('captains').doc(uid).update({ accountStatus: 'approved' }));
    await assertFails(capDb.collection('captains').doc(uid).update({ accountStatus: 'approved', isApproved: true }));
    await assertFails(capDb.collection('captains').doc(uid).update({ status: 'active' }));
    await assertFails(capDb.collection('captains').doc(uid).update({ rejectionReason: 'cleared' }));
    await assertFails(capDb.collection('captains').doc(uid).update({ isOnline: true }));
    await assertFails(capDb.collection('captains').doc(uid).update({ isLoggedIn: true }));
    await assertFails(capDb.collection('captains').doc(uid).update({ presenceStatus: 'available' }));
    await assertFails(capDb.collection('captains').doc(uid).update({ latitude: 30.05, longitude: 31.23 }));
    await assertFails(capDb.collection('orders').doc('ord_for_pending_cap').get());
    await assertFails(capDb.collection('captain_schedules').doc(uid).get());
    await assertFails(capDb.collection('captains').doc(uid).collection('transactions').doc('tx_1').get());

    // 7e. Admin rejects captain -> captain can still read rejection reason on own doc, still blocked from all operations
    await assertSucceeds(adminDb.collection('captains').doc(uid).update({
      accountStatus: 'rejected',
      isApproved: false,
      status: 'rejected',
      rejectionReason: 'صورة البطاقة غير واضحة',
    }));
    const rejectedSnap = await assertSucceeds(capDb.collection('captains').doc(uid).get());
    assert.equal(rejectedSnap.data().rejectionReason, 'صورة البطاقة غير واضحة');
    await assertFails(capDb.collection('captains').doc(uid).update({ isOnline: true }));
    await assertFails(capDb.collection('orders').doc('ord_for_pending_cap').get());

    // 7f. Admin approves captain -> captain immediately gains operational access (online, GPS, own offered orders, schedule, wallet)
    await assertSucceeds(adminDb.collection('captains').doc(uid).update({
      accountStatus: 'approved',
      isApproved: true,
      status: 'active',
      rejectionReason: '',
    }));
    await assertSucceeds(capDb.collection('captains').doc(uid).update({
      isOnline: true,
      isLoggedIn: true,
      presenceStatus: 'available',
      latitude: 30.05,
      longitude: 31.23,
    }));
    await assertSucceeds(capDb.collection('orders').doc('ord_for_pending_cap').get());
    await assertSucceeds(capDb.collection('captain_schedules').doc(uid).get());
    await assertSucceeds(capDb.collection('captains').doc(uid).collection('transactions').doc('tx_1').get());

    // Even after approval, captain STILL cannot tamper with accountStatus/isApproved
    await assertFails(capDb.collection('captains').doc(uid).update({ accountStatus: 'pending' }));
    await assertFails(capDb.collection('captains').doc(uid).update({ isApproved: false }));

    // 7g. Admin suspends or blocks captain -> immediately revokes operational access
    await assertSucceeds(adminDb.collection('captains').doc(uid).update({
      accountStatus: 'suspended',
      isApproved: false,
      status: 'suspended',
      isOnline: false,
      presenceStatus: 'offline',
    }));
    await assertFails(capDb.collection('captains').doc(uid).update({ isOnline: true }));
    await assertFails(capDb.collection('captains').doc(uid).update({ latitude: 30.06, longitude: 31.24 }));
    await assertFails(capDb.collection('orders').doc('ord_for_pending_cap').get());
    await assertFails(capDb.collection('captain_schedules').doc(uid).get());
    // Captain can still read own doc to see suspended state
    const suspendedSnap = await assertSucceeds(capDb.collection('captains').doc(uid).get());
    assert.equal(suspendedSnap.data().accountStatus, 'suspended');
  });
});
