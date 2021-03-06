/*
 * Wi-Spy DBx interface, for third-gen Wi-Spy hardware
 *
 * 255 samples, variable resolution
 *
 * Device configured via HID feature SET
 * Device read via blocking USB endpoint iread
 *
 * Wi-Spy (tm) metageek LLC
 *
 * This code is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Extra thanks to all @ Metageek for interface documentation
 */

#ifndef __WISPY_HW_DBX_H__
#define __WISPY_HW_DBX_H__

#include "spectool_container.h"

/* Wispy1 device scan results */
typedef struct _wispydbx_usb_pair {
	char bus[64];
	char dev[64];
} wispydbx_usb_pair;

int wispydbx_usb_device_scan(wispy_device_list *list);

/* Wispy24x init function to build a phydev linked to a bus and device path
 * scanned */
int wispydbx_usb_init_path(wispy_phy *phydev, char *buspath, char *devpath);
int wispydbx_usb_init(wispy_phy *phydev, wispy_device_rec *rec);

#endif
