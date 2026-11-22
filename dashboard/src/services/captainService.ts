import {
  collection,
  doc,
  getDoc,
  getDocs,
  setDoc,
  updateDoc,
  deleteDoc,
  deleteField,
  serverTimestamp,
} from 'firebase/firestore';
import { auth, db, isFirebaseConfigured, handleFirestoreError, OperationType } from '../firebase/config';
import { COLLECTIONS } from './collections';
import { Captain, CaptainAccountStatus, CaptainStatus } from '../types';
import { extractCoordinates } from '../utils/geoUtils';

let localCaptainsCache: Captain[] = [];

function formatFirestoreDate(val: any): string {
  if (!val) return '';
  if (typeof val?.toDate === 'function') {
    return val.toDate().toISOString();
  }
  if (typeof val === 'object' && typeof val.seconds === 'number') {
    return new Date(val.seconds * 1000).toISOString();
  }
  return String(val);
}

function isLocalDeviceUri(val: unknown): boolean {
  if (typeof val !== 'string') return false;
  const lower = val.trim().toLowerCase();
  return (
    lower.startsWith('content://') ||
    lower.startsWith('file://') ||
    lower.startsWith('android.resource://')
  );
}

function pickFirstValidImage(candidates: unknown[]): string {
  for (const item of candidates) {
    if (typeof item === 'string') {
      const trimmed = item.trim();
      if (trimmed && !isLocalDeviceUri(trimmed)) {
        return trimmed;
      }
    }
  }
  return '';
}

export function resolveCaptainAccountApproval(data: Record<string, any>): {
  accountStatus: CaptainAccountStatus;
  isApproved: boolean;
  rejectionReason: string;
} {
  const rejectionReason = typeof data.rejectionReason === 'string' ? data.rejectionReason.trim() : '';
  if (data.isBlocked === true) {
    return { accountStatus: 'blocked', isApproved: false, rejectionReason };
  }
  if (data.isSuspended === true) {
    return { accountStatus: 'suspended', isApproved: false, rejectionReason };
  }
  const explicitStatus = typeof data.accountStatus === 'string' ? data.accountStatus.trim().toLowerCase() : '';
  if (explicitStatus) {
    if (explicitStatus === 'approved') {
      return data.isApproved === false
        ? { accountStatus: 'pending', isApproved: false, rejectionReason }
        : { accountStatus: 'approved', isApproved: true, rejectionReason: '' };
    }
    if (explicitStatus === 'pending') return { accountStatus: 'pending', isApproved: false, rejectionReason };
    if (explicitStatus === 'rejected') return { accountStatus: 'rejected', isApproved: false, rejectionReason };
    if (explicitStatus === 'suspended') return { accountStatus: 'suspended', isApproved: false, rejectionReason };
    if (explicitStatus === 'blocked' || explicitStatus === 'banned' || explicitStatus === 'disabled') {
      return { accountStatus: 'blocked', isApproved: false, rejectionReason };
    }
    return { accountStatus: 'pending', isApproved: false, rejectionReason };
  }

  if (data.isApproved === false) {
    return { accountStatus: 'pending', isApproved: false, rejectionReason };
  }

  const legacyStatus = typeof data.status === 'string' ? data.status.trim().toLowerCase() : '';
  if (legacyStatus === 'rejected') return { accountStatus: 'rejected', isApproved: false, rejectionReason };
  if (legacyStatus === 'suspended') return { accountStatus: 'suspended', isApproved: false, rejectionReason };
  if (legacyStatus === 'blocked' || legacyStatus === 'banned' || legacyStatus === 'disabled') {
    return { accountStatus: 'blocked', isApproved: false, rejectionReason };
  }
  if (['active', 'approved', 'online', 'available', 'busy'].includes(legacyStatus)) {
    return { accountStatus: 'approved', isApproved: true, rejectionReason: '' };
  }
  if (!legacyStatus && data.isApproved === true) {
    return { accountStatus: 'approved', isApproved: true, rejectionReason: '' };
  }
  return { accountStatus: 'pending', isApproved: false, rejectionReason };
}

function mapDocToCaptain(id: string, data: Record<string, any>): Captain {
  const profileImage = pickFirstValidImage([
    data.profileImagePath,
    data.profile_image_path,
    data.profileImage,
    data.profile_image,
    data.profileImageUrl,
    data.profile_image_url,
    data.photoURL,
    data.photoUrl,
    data.photo_url,
    data.avatarUri,
    data.avatar_uri,
    data.avatarUrl,
    data.avatar_url,
    data.avatar,
    data.image,
    data.imageUrl,
    data.image_url,
    data.personalPhoto,
    data.personal_photo,
    data.captainImage,
    data.captain_image,
    data.driverImage,
    data.driver_image,
    data.photo,
    data.picture,
    data.userImage,
    data.profile?.profileImagePath,
    data.profile?.photoURL,
    data.profile?.photoUrl,
  ]);

  const email =
    data.email ||
    data.captainEmail ||
    data.userEmail ||
    data.contactEmail ||
    '';

  const password =
    data.password ||
    data.captainPassword ||
    data.userPassword ||
    data.pass ||
    data.pin ||
    '';

  const rawStatus = (data.status as CaptainStatus) || (data.isOnline ? 'online' : 'offline');

  // Determine whether captain is allowed to receive orders
  let canReceiveOrders = true;
  if (typeof data.canReceiveOrders === 'boolean') {
    canReceiveOrders = data.canReceiveOrders;
  } else if (typeof data.isReceivingOrders === 'boolean') {
    canReceiveOrders = data.isReceivingOrders;
  } else if (typeof data.receiveOrders === 'boolean') {
    canReceiveOrders = data.receiveOrders;
  } else if (data.isBlocked === true || data.isSuspended === true || data.orderReceptionDisabled === true) {
    canReceiveOrders = false;
  }

  const createdAt = formatFirestoreDate(data.createdAt) || new Date().toISOString();
  const updatedAt = formatFirestoreDate(data.updatedAt) || createdAt;
  const statusUpdatedAt =
    formatFirestoreDate(data.statusUpdatedAt || data.lastStatusUpdate || data.updatedAt || data.createdAt) ||
    createdAt;

  const coords = extractCoordinates(data);
  const approval = resolveCaptainAccountApproval(data);
  const idFrontImagePath = pickFirstValidImage([data.idFrontImagePath, data.idCardFront]);
  const idBackImagePath = pickFirstValidImage([data.idBackImagePath, data.idCardBack]);
  const licenseImagePath = pickFirstValidImage([data.licenseImagePath, data.driverLicenseImagePath]);

  return {
    id,
    name: data.name || data.fullName || data.displayName || 'كابتن بدون اسم',
    phone: data.phone || data.phoneNumber || data.mobile || '—',
    email: email || '—',
    password: password ? String(password) : '',
    profileImage,
    photoURL: profileImage,
    idFrontImagePath,
    idBackImagePath,
    licenseImagePath,
    status: rawStatus,
    accountStatus: approval.accountStatus,
    isApproved: approval.isApproved,
    rejectionReason: approval.rejectionReason || undefined,
    approvedAt: formatFirestoreDate(data.approvedAt) || undefined,
    approvedBy: typeof data.approvedBy === 'string' ? data.approvedBy : undefined,
    isOnline: Boolean(data.isOnline ?? rawStatus === 'online'),
    canReceiveOrders: approval.isApproved ? canReceiveOrders : false,
    ordersCount:
      typeof data.ordersCount === 'number'
        ? data.ordersCount
        : typeof data.totalOrdersCount === 'number'
        ? data.totalOrdersCount
        : typeof data.totalDeliveries === 'number'
        ? data.totalDeliveries
        : typeof data.completedOrders === 'number'
        ? data.completedOrders
        : 0,
    rating: typeof data.rating === 'number' ? data.rating : 5.0,
    createdAt,
    updatedAt,
    statusUpdatedAt,
    vehicleType: data.vehicleType || data.vehicle || 'دراجة نارية',
    location: data.location || data.coordinates || data.coords,
    coords,
    lastLocationUpdate: data.lastLocationUpdate || data.lastUpdate || data.updatedAt,
    raw: data,
  };
}

export const CaptainService = {
  async getAll(): Promise<{ data: Captain[]; isLive: boolean }> {
    if (!isFirebaseConfigured) {
      return { data: localCaptainsCache, isLive: false };
    }

    try {
      const snapshot = await getDocs(collection(db, COLLECTIONS.CAPTAINS));

      if (snapshot.empty) {
        localCaptainsCache = [];
        return { data: [], isLive: true };
      }

      const list: Captain[] = [];
      snapshot.forEach((docSnap) => {
        list.push(mapDocToCaptain(docSnap.id, docSnap.data()));
      });

      list.sort((a, b) => a.name.localeCompare(b.name, 'ar'));
      localCaptainsCache = list;
      return { data: list, isLive: true };
    } catch (error) {
      handleFirestoreError(error, OperationType.LIST, COLLECTIONS.CAPTAINS);
      return { data: localCaptainsCache, isLive: false };
    }
  },

  async updateStatus(id: string, status: CaptainStatus): Promise<void> {
    const nowIso = new Date().toISOString();
    localCaptainsCache = localCaptainsCache.map((c) =>
      c.id === id ? { ...c, status, isOnline: status === 'online', statusUpdatedAt: nowIso } : c
    );

    if (isFirebaseConfigured) {
      try {
        const docRef = doc(db, COLLECTIONS.CAPTAINS, id);
        await updateDoc(docRef, {
          status,
          isOnline: status === 'online',
          statusUpdatedAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        });
      } catch (error) {
        handleFirestoreError(error, OperationType.UPDATE, `${COLLECTIONS.CAPTAINS}/${id}`);
      }
    }
  },

  async reviewAccountStatus(
    id: string,
    accountStatus: CaptainAccountStatus,
    rejectionReason?: string
  ): Promise<void> {
    const approved = accountStatus === 'approved';
    const cleanReason = (rejectionReason || '').trim().slice(0, 500);
    const nowIso = new Date().toISOString();
    const adminUid = auth?.currentUser?.uid || 'admin';
    const nextOperationalStatus: CaptainStatus = approved ? 'active' : accountStatus;

    localCaptainsCache = localCaptainsCache.map((c) =>
      c.id === id
        ? {
            ...c,
            accountStatus,
            isApproved: approved,
            status: nextOperationalStatus,
            rejectionReason: approved ? undefined : cleanReason || c.rejectionReason,
            approvedAt: approved ? nowIso : c.approvedAt,
            approvedBy: approved ? adminUid : c.approvedBy,
            isOnline: approved ? c.isOnline : false,
            canReceiveOrders: approved,
            statusUpdatedAt: nowIso,
            updatedAt: nowIso,
          }
        : c
    );

    if (isFirebaseConfigured) {
      try {
        const docRef = doc(db, COLLECTIONS.CAPTAINS, id);
        const payload: Record<string, any> = {
          accountStatus,
          isApproved: approved,
          status: nextOperationalStatus,
          isBlocked: accountStatus === 'blocked',
          isSuspended: accountStatus === 'suspended',
          canReceiveOrders: approved,
          isReceivingOrders: approved,
          receiveOrders: approved,
          orderReceptionDisabled: !approved,
          reviewedAt: serverTimestamp(),
          reviewedBy: adminUid,
          statusUpdatedAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        };

        if (approved) {
          payload.approvedAt = serverTimestamp();
          payload.approvedBy = adminUid;
          payload.rejectionReason = deleteField();
        } else {
          payload.isOnline = false;
          payload.isLoggedIn = false;
          payload.presenceStatus = 'offline';
          payload.availabilityStatus = 'offline';
          if (accountStatus === 'rejected') {
            payload.rejectedAt = serverTimestamp();
            payload.rejectedBy = adminUid;
            payload.rejectionReason = cleanReason || deleteField();
          } else if (accountStatus === 'suspended') {
            payload.suspendedAt = serverTimestamp();
            payload.suspendedBy = adminUid;
            if (cleanReason) payload.rejectionReason = cleanReason;
          } else if (accountStatus === 'blocked') {
            payload.blockedAt = serverTimestamp();
            payload.blockedBy = adminUid;
            if (cleanReason) payload.rejectionReason = cleanReason;
          }
        }

        await updateDoc(docRef, payload);
      } catch (error) {
        handleFirestoreError(error, OperationType.UPDATE, `${COLLECTIONS.CAPTAINS}/${id}`);
        throw error;
      }
    }
  },

  async toggleOrderReception(id: string, canReceiveOrders: boolean): Promise<void> {
    const nowIso = new Date().toISOString();
    const newStatus: CaptainStatus = canReceiveOrders ? 'online' : 'offline';

    localCaptainsCache = localCaptainsCache.map((c) =>
      c.id === id
        ? {
            ...c,
            canReceiveOrders,
            status: newStatus,
            isOnline: canReceiveOrders,
            statusUpdatedAt: nowIso,
          }
        : c
    );

    if (isFirebaseConfigured) {
      try {
        const docRef = doc(db, COLLECTIONS.CAPTAINS, id);
        await updateDoc(docRef, {
          canReceiveOrders,
          isReceivingOrders: canReceiveOrders,
          receiveOrders: canReceiveOrders,
          isBlocked: !canReceiveOrders,
          status: newStatus,
          isOnline: canReceiveOrders,
          statusUpdatedAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        });
      } catch (error) {
        handleFirestoreError(error, OperationType.UPDATE, `${COLLECTIONS.CAPTAINS}/${id}`);
        throw error;
      }
    }
  },

  async updatePassword(id: string, newPassword: string): Promise<void> {
    const nowIso = new Date().toISOString();
    localCaptainsCache = localCaptainsCache.map((c) =>
      c.id === id ? { ...c, password: newPassword, updatedAt: nowIso } : c
    );

    if (isFirebaseConfigured) {
      try {
        const docRef = doc(db, COLLECTIONS.CAPTAINS, id);
        await updateDoc(docRef, {
          password: newPassword,
          captainPassword: newPassword,
          updatedAt: serverTimestamp(),
        });

        // Also sync to users collection if a corresponding user profile document exists
        try {
          const userDocRef = doc(db, COLLECTIONS.USERS, id);
          const userSnap = await getDoc(userDocRef);
          if (userSnap.exists()) {
            await updateDoc(userDocRef, {
              password: newPassword,
              updatedAt: serverTimestamp(),
            });
          }
        } catch {
          // Ignore if users doc doesn't exist
        }
      } catch (error) {
        handleFirestoreError(error, OperationType.UPDATE, `${COLLECTIONS.CAPTAINS}/${id}`);
        throw error;
      }
    }
  },

  async updateCaptain(id: string, updates: Partial<Captain>): Promise<void> {
    const nowIso = new Date().toISOString();
    localCaptainsCache = localCaptainsCache.map((c) =>
      c.id === id ? { ...c, ...updates, updatedAt: nowIso } : c
    );

    if (isFirebaseConfigured) {
      try {
        const docRef = doc(db, COLLECTIONS.CAPTAINS, id);
        const payload: Record<string, any> = {
          updatedAt: serverTimestamp(),
        };

        if (updates.name !== undefined) payload.name = updates.name;
        if (updates.phone !== undefined) payload.phone = updates.phone;
        if (updates.email !== undefined) payload.email = updates.email;
        if (updates.vehicleType !== undefined) payload.vehicleType = updates.vehicleType;
        if (updates.password !== undefined) {
          payload.password = updates.password;
          payload.captainPassword = updates.password;
        }
        if (updates.profileImage !== undefined) {
          payload.profileImage = updates.profileImage;
          payload.photoURL = updates.profileImage;
          payload.profileImagePath = updates.profileImage;
        }
        if (updates.status !== undefined) {
          payload.status = updates.status;
          payload.isOnline = updates.status === 'online';
          payload.statusUpdatedAt = serverTimestamp();
        }
        if (updates.canReceiveOrders !== undefined) {
          payload.canReceiveOrders = updates.canReceiveOrders;
          payload.isReceivingOrders = updates.canReceiveOrders;
          payload.receiveOrders = updates.canReceiveOrders;
          payload.isBlocked = !updates.canReceiveOrders;
          payload.statusUpdatedAt = serverTimestamp();
        }

        await updateDoc(docRef, payload);
      } catch (error) {
        handleFirestoreError(error, OperationType.UPDATE, `${COLLECTIONS.CAPTAINS}/${id}`);
        throw error;
      }
    }
  },

  async create(data: Omit<Captain, 'id' | 'accountStatus' | 'isApproved'> & { accountStatus?: CaptainAccountStatus; isApproved?: boolean }): Promise<Captain> {
    const newId = `capt_${Date.now()}`;
    const nowIso = new Date().toISOString();
    const adminUid = auth?.currentUser?.uid || 'admin';
    const accountStatus: CaptainAccountStatus = data.accountStatus || 'approved';
    const isApproved = data.isApproved ?? accountStatus === 'approved';
    const canReceive = isApproved
      ? data.canReceiveOrders !== undefined
        ? data.canReceiveOrders
        : data.status !== 'offline'
      : false;

    const newCaptain: Captain = {
      ...data,
      id: newId,
      accountStatus,
      isApproved,
      approvedAt: isApproved ? nowIso : undefined,
      approvedBy: isApproved ? adminUid : undefined,
      canReceiveOrders: canReceive,
      createdAt: nowIso,
      updatedAt: nowIso,
      statusUpdatedAt: nowIso,
    };

    localCaptainsCache = [newCaptain, ...localCaptainsCache];

    if (isFirebaseConfigured) {
      try {
        const docRef = doc(db, COLLECTIONS.CAPTAINS, newId);
        await setDoc(docRef, {
          uid: newId,
          accountType: 'captain',
          role: 'captain',
          name: data.name,
          fullName: data.name,
          phone: data.phone,
          email: data.email || '',
          password: data.password || '',
          captainPassword: data.password || '',
          profileImage: data.profileImage || '',
          photoURL: data.profileImage || '',
          profileImagePath: data.profileImage || '',
          status: isApproved ? data.status : accountStatus,
          accountStatus,
          isApproved,
          approvedAt: isApproved ? serverTimestamp() : null,
          approvedBy: isApproved ? adminUid : null,
          isOnline: isApproved && data.status === 'online',
          canReceiveOrders: canReceive,
          isReceivingOrders: canReceive,
          receiveOrders: canReceive,
          isBlocked: accountStatus === 'blocked',
          isSuspended: accountStatus === 'suspended',
          ordersCount: data.ordersCount || 0,
          rating: data.rating || 5.0,
          vehicleType: data.vehicleType || 'دراجة نارية',
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
          statusUpdatedAt: serverTimestamp(),
        });
      } catch (error) {
        handleFirestoreError(error, OperationType.CREATE, `${COLLECTIONS.CAPTAINS}/${newId}`);
      }
    }

    return newCaptain;
  },

  async delete(id: string): Promise<void> {
    localCaptainsCache = localCaptainsCache.filter((c) => c.id !== id);

    if (isFirebaseConfigured) {
      try {
        const docRef = doc(db, COLLECTIONS.CAPTAINS, id);
        await deleteDoc(docRef);
      } catch (error) {
        handleFirestoreError(error, OperationType.DELETE, `${COLLECTIONS.CAPTAINS}/${id}`);
      }
    }
  },
};
